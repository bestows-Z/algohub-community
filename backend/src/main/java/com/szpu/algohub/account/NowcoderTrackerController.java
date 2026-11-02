package com.szpu.algohub.account;

import com.fasterxml.jackson.databind.*;
import com.szpu.algohub.auth.CurrentUser;
import com.szpu.algohub.auth.AuthService;
import com.szpu.algohub.common.*;
import com.szpu.algohub.platform.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/accounts/nowcoder-tracker")
public class NowcoderTrackerController {
    private final PlatformAccountMapper accounts;
    private final PlatformHttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Value("${algohub.sync.enabled:true}") private boolean enabled;
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(r -> {var t=new Thread(r,"tracker-cache");t.setDaemon(true);return t;});
    private final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();
    @jakarta.annotation.PreDestroy public void close(){worker.shutdownNow();}
    private final IdentityLookupGuard lookupGuard;
    private final AuthService authService;
    private final com.szpu.algohub.problem.NowcoderWriteTransactions writes;
    public NowcoderTrackerController(PlatformAccountMapper accounts, PlatformHttpClient http, org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.transaction.support.TransactionTemplate tx, IdentityLookupGuard lookupGuard, AuthService authService, com.szpu.algohub.problem.NowcoderWriteTransactions writes) { this.writes=writes; this.accounts=accounts; this.http=http; this.jdbc=jdbc;this.tx=tx;this.lookupGuard=lookupGuard;this.authService=authService; }
    public record Problem(String pid, String title, String url, String difficulty) {}
    public record Page(List<Problem> accepted, int page, int totalContests, int scannedQuestions, boolean hasMore) {}
    @PostMapping("/sync")
    public ApiResult<String> requestSync() {
        var account=accounts.findByUser(CurrentUser.requireId()).stream().filter(a -> "NOWCODER".equals(a.getPlatform())).findFirst()
                .orElseThrow(() -> ApiException.validation("请先绑定牛客数字 UID"));
        if(!enabled) throw ApiException.validation("后台同步当前已停用");
        jdbc.update("insert into nowcoder_tracker_progress(account_id) values (?) on conflict do nothing",account.getId());
        int queued=jdbc.update("update nowcoder_tracker_progress set complete=false,next_page=1,completed_pages=0,next_run_at=now() where account_id=? and complete=true and error_message is null",account.getId());
        return ApiResult.ok(queued>0 ? "已安排新一轮同步，已有题目会保留" : "任务已在队列中；失败任务会按退避时间继续，无需重复点击");
    }
    @GetMapping("/problems")
    public ApiResult<Page> problems(@RequestParam(defaultValue="1") int page) {
        if(page<1 || page>10000) throw ApiException.validation("页码无效");
        Long userId=CurrentUser.requireId();
        var account=accounts.findByUser(userId).stream().filter(a -> "NOWCODER".equals(a.getPlatform())).findFirst()
                .orElseThrow(() -> ApiException.validation("请先绑定牛客数字 UID"));
        // 一页是 1 次比赛目录 + 若干次通过情况查询，按 3 次计
        lookupGuard.acquire(userId,3);
        return ApiResult.ok(fetchPage(account,page));
    }
    private Page fetchPage(PlatformAccount account,int page) {
        try {
            JsonNode root=json.readTree(http.get(Platform.NOWCODER,"https://www.nowcoder.com/problem/tracker/list?contestType=0&page="+page+"&limit=30"));
            JsonNode data=root.path("data"), papers=data.path("papers");
            if(root.path("code").asInt(-1)!=0 || !papers.isArray() || !data.path("totalCount").canConvertToInt()) throw new IllegalArgumentException();
            int total=data.path("totalCount").asInt();
            if(total<0 || (papers.isEmpty() && (page-1)*30<total)) throw new IllegalArgumentException("比赛目录缺页");
            for(JsonNode paper:papers) if(!paper.path("questions").isArray()) throw new IllegalArgumentException("比赛题目字段缺失");
            Map<String,Problem> candidates=new LinkedHashMap<>();
            for(JsonNode paper:papers) for(JsonNode p:paper.path("questions")) {
                String id=p.path("problemId").asText(),url=p.path("questionUrl").asText();
                if(!id.matches("\\d+") || !url.startsWith("https://www.nowcoder.com/practice/")) continue;
                candidates.put(id,new Problem(id,p.path("title").asText(),url,
                        com.szpu.algohub.platform.adapter.NowcoderAdapter.ratedDifficulty(p.path("difficulty").asText(null))));
            }
            Set<String> accepted=new HashSet<>();
            var ids=new ArrayList<>(candidates.keySet());
            for(int start=0;start<ids.size();start+=300) {
                JsonNode diff=json.readTree(http.postForm(Platform.NOWCODER,"https://www.nowcoder.com/problem/tracker/diff",
                        Map.of("userId1",account.getPlatformUid(),"qids",String.join(",",ids.subList(start,Math.min(start+300,ids.size())))),Map.of(),null));
                if(diff.path("code").asInt(-1)!=0 || !diff.path("data").path("ac1Qids").isArray()) throw new IllegalArgumentException();
                for(JsonNode id:diff.path("data").path("ac1Qids")) accepted.add(id.asText());
            }
            var result=new Page(candidates.values().stream().filter(p -> accepted.contains(p.pid())).toList(),page,total,candidates.size(),page*30<total);
            tx.executeWithoutResult(status -> {
                for(var p:candidates.values()) {
                    if(accepted.contains(p.pid())) jdbc.update("insert into nowcoder_tracker_problem(account_id,pid,title,url,difficulty) values (?,?,?,?,?) on conflict(account_id,pid) do update set title=excluded.title,url=excluded.url,difficulty=excluded.difficulty,updated_at=now()",account.getId(),p.pid(),p.title(),p.url(),p.difficulty());
                    else jdbc.update("delete from nowcoder_tracker_problem where account_id=? and pid=?",account.getId(),p.pid());
                }
            });
            return result;
        } catch(ApiException e) { throw e; }
        catch(Exception e) { throw new ApiException(ErrorKind.PARSE_ERROR,"Tracker 题目读取失败，未将失败记为 0 题"); }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=5000,initialDelay=15000)
    public void schedule() {
        if(!enabled || !running.compareAndSet(false,true)) return;
        worker.submit(() -> { try { syncNext(); } finally { running.set(false); } });
    }
    private void syncNext() {
        mergeCachedSolved();
        jdbc.update("insert into nowcoder_tracker_progress(account_id) select id from platform_account where platform='NOWCODER' on conflict do nothing");
        var due=jdbc.queryForList("select account_id,next_page from nowcoder_tracker_progress where next_run_at<=now() order by next_run_at limit 1");
        if(due.isEmpty()) return;
        Long id=((Number)due.getFirst().get("account_id")).longValue();
        var account=accounts.findById(id); if(account==null) return;
        int page=((Number)due.getFirst().get("next_page")).intValue();
        try {
            Page result=fetchPage(account,page);
            if (!result.hasMore()) syncPractice(account);
            mergeCachedSolved();
            jdbc.update("update nowcoder_tracker_progress set next_page=?,completed_pages=?,total_contests=?,complete=?,last_success_at=now(),error_message=null,next_run_at=now()+(? * interval '1 second') where account_id=?",
                    result.hasMore()?page+1:1,page,result.totalContests(),!result.hasMore(),result.hasMore()?5:2700,id);
        } catch(Exception e) {
            jdbc.update("update nowcoder_tracker_progress set error_message='源站暂不可用，已保留题目缓存，5 分钟后继续',next_run_at=now()+interval '5 minutes' where account_id=?",id);
        }
    }
    /** 按 {@link NowcoderTrackerMapping} 合并：同题（题号与标题都一致）只记一次，题号撞了但标题不同的另记。 */
    void mergeCachedSolved() {
        writes.execute("NOWCODER", () -> {
            jdbc.update(NowcoderTrackerMapping.MAPPED + """
                insert into problem(platform,problem_key,title,url,difficulty_raw,difficulty_label,difficulty_value,difficulty_source,tags_complete)
                select distinct on (m.problem_key) 'NOWCODER',m.problem_key,m.title,m.url,
                       case when m.difficulty ~ '^\\d{1,9}$' then m.difficulty end,
                       case when m.difficulty ~ '^\\d{1,9}$' then m.difficulty end,
                       case when m.difficulty ~ '^\\d{1,9}$' then m.difficulty::int end,'OFFICIAL',false
                  from mapped m order by m.problem_key,m.updated_at desc
                on conflict(platform,problem_key) do update
                   set difficulty_raw=excluded.difficulty_raw,
                       difficulty_label=excluded.difficulty_label,
                       difficulty_value=excluded.difficulty_value,
                       difficulty_source=excluded.difficulty_source,
                       updated_at=now()
                 where problem.catalog_synced_at is null and excluded.difficulty_value is not null
                   and (problem.difficulty_label is distinct from excluded.difficulty_label
                        or problem.difficulty_value is distinct from excluded.difficulty_value)
                """);
            jdbc.update(NowcoderTrackerMapping.MAPPED + """
                insert into user_solved_problem(user_id,account_id,problem_id,platform,first_ac_confidence,source)
                select a.user_id,a.id,p.id,'NOWCODER','UNKNOWN','TRACKER'
                  from mapped m join platform_account a on a.id=m.account_id
                  join problem p on p.platform='NOWCODER' and p.problem_key=m.problem_key
                on conflict(account_id,problem_id) do nothing
                """);
            // 映射变了（例如竞赛题库后来给同一题号换成了别的标题）：原来那条 Tracker 记录撤掉，已按新映射另记
            jdbc.update(NowcoderTrackerMapping.MAPPED + """
                delete from user_solved_problem s where s.source='TRACKER'
                  and not exists(select 1 from mapped m join problem p on p.platform='NOWCODER' and p.problem_key=m.problem_key where m.account_id=s.account_id and p.id=s.problem_id)
                  and not exists(select 1 from submission sub where sub.account_id=s.account_id and sub.problem_id=s.problem_id and sub.accepted)
                """);
            return null;
        });
    }
    private void syncPractice(PlatformAccount account) throws Exception {
        JsonNode catalog=json.readTree(http.get(Platform.NOWCODER,"https://static.nowcoder.com/book/tracker/parsed_practice_problems.json"));
        var candidates=new LinkedHashMap<String,Problem>();
        for(JsonNode node:catalog.findParents("problemId")) {
            String id=node.path("problemId").asText(),url=node.path("url").asText();
            if(id.matches("\\d+") && url.startsWith("https://www.nowcoder.com/practice/"))
                candidates.put(id,new Problem(id,node.path("title").asText(),url,
                        com.szpu.algohub.platform.adapter.NowcoderAdapter.ratedDifficulty(node.path("difficulty").asText(null))));
        }
        if(candidates.isEmpty()) throw new IllegalArgumentException("练习目录为空");
        var ids=new ArrayList<>(candidates.keySet());
        for(int start=0;start<ids.size();start+=300) {
            var batch=ids.subList(start,Math.min(start+300,ids.size()));
            JsonNode diff=json.readTree(http.postForm(Platform.NOWCODER,"https://www.nowcoder.com/problem/tracker/diff",
                    Map.of("userId1",account.getPlatformUid(),"qids",String.join(",",batch)),Map.of(),null));
            if(diff.path("code").asInt(-1)!=0 || !diff.path("data").path("ac1Qids").isArray()) throw new IllegalArgumentException();
            Set<String> accepted=new HashSet<>();
            for(JsonNode id:diff.path("data").path("ac1Qids")) accepted.add(id.asText());
            tx.executeWithoutResult(status -> {
                for(String id:batch) {
                    var p=candidates.get(id);
                    if(accepted.contains(id)) jdbc.update("insert into nowcoder_tracker_problem(account_id,pid,title,url,difficulty) values (?,?,?,?,?) on conflict(account_id,pid) do update set title=excluded.title,url=excluded.url,difficulty=excluded.difficulty,updated_at=now()",account.getId(),id,p.title(),p.url(),p.difficulty());
                    else jdbc.update("delete from nowcoder_tracker_problem where account_id=? and pid=?",account.getId(),id);
                }
            });
        }
    }
    @GetMapping("/cache")
    public ApiResult<Map<String,Object>> cache() {
        return cacheFor(CurrentUser.requireId(), false);
    }
    @GetMapping("/cache/{userId}")
    public ApiResult<Map<String,Object>> publicCache(@PathVariable Long userId) {
        Long viewerId = CurrentUser.requireId();
        authService.requireReadableProfile(userId, viewerId, authService.canReadPrivateProfiles(viewerId));
        return cacheFor(userId, true);
    }
    private ApiResult<Map<String,Object>> cacheFor(Long user, boolean verifiedOnly) {
        var progress=jdbc.queryForList("select p.completed_pages as \"completedPages\",p.total_contests as \"totalContests\",p.complete,p.last_success_at as \"lastSuccessAt\",p.next_run_at as \"nextRunAt\",p.error_message as \"errorMessage\" from nowcoder_tracker_progress p join platform_account a on a.id=p.account_id where a.user_id=? and (?=false or a.ownership_status='VERIFIED')",user,verifiedOnly);
        var problems=jdbc.queryForList("select t.pid,t.title,t.url,t.difficulty from nowcoder_tracker_problem t join platform_account a on a.id=t.account_id where a.user_id=? and (?=false or a.ownership_status='VERIFIED') order by t.pid",user,verifiedOnly);
        return ApiResult.ok(Map.of("problems",problems,"progress",progress));
    }
}
