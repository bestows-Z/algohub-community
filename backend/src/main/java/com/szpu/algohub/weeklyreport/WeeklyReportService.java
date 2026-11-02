package com.szpu.algohub.weeklyreport;

import static com.szpu.algohub.weeklyreport.ReportTime.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.common.*;

@Service
@Transactional
public class WeeklyReportService {
    private final JdbcTemplate db;
    private final ReportAccess access;
    private final ReportTime clock;
    public WeeklyReportService(JdbcTemplate db,ReportAccess access,ReportTime clock) { this.db=db;this.access=access;this.clock=clock; }
    public record Draft(Long id,String weekStart,int version,String summary,String problems,String plan) {}
    public record Decision(int version,boolean returned,String comment) {}
    static String text(String value,int max) {
        String s=value==null?"":value.strip();
        if(s.length()>max) throw ApiException.validation("内容超过允许长度 "+max+" 字");
        return s;
    }
    public Map<String,Object> context(long actor) {
        Instant now=clock.now();
        var result=new LinkedHashMap<String,Object>(); result.put("weekStart",week(now));result.put("timezone","Asia/Shanghai");
        var members=db.queryForList("""
            select m.school_id as "schoolId",s.name as "schoolName" from school_member m join school s on s.id=m.school_id
            join app_user u on u.id=m.user_id where m.user_id=? and m.status='APPROVED' and u.status='ACTIVE' and u.role='STUDENT'
            and not exists(select 1 from school_administrator a where a.user_id=u.id)
            """,actor);
        result.put("membership",members.isEmpty()?null:members.getFirst());
        result.put("deadline",null);
        if(!members.isEmpty()) {
            long school=((Number)members.getFirst().get("schoolId")).longValue();
            var cycles=db.queryForList("""
                select c.cutoff_at from weekly_report_cycle c join weekly_report_setting s on s.school_id=c.school_id
                where c.school_id=? and c.week_start=? and s.enabled and c.status<>'CANCELLED'
                """,school,week(now));
            if(!cycles.isEmpty()) result.put("deadline",cycles.getFirst().get("cutoff_at"));
        }
        return result;
    }
    public Map<String,Object> mine(long actor,int page) {
        checkPage(page);
        return Map.of("rows",db.queryForList("""
            select r.id,r.school_id as "schoolId",s.name as "schoolName",r.week_start as "weekStart",r.status,r.version,
            r.updated_at as "updatedAt",r.submitted_at as "submittedAt" from weekly_report r join school s on s.id=r.school_id
            where r.user_id=? and r.deleted_at is null order by r.week_start desc,r.id desc limit 20 offset ?
            """,actor,(page-1)*20),"total",db.queryForObject("select count(*) from weekly_report where user_id=? and deleted_at is null",Long.class,actor));
    }
    public Map<String,Object> own(long actor,long id) {
        var rows=db.queryForList("select * from weekly_report where id=? and user_id=? and deleted_at is null",id,actor);
        if(rows.isEmpty()) throw ApiException.notFound("周报不存在");
        return detail(rows.getFirst(),true);
    }
    public Map<String,Object> find(long actor,String weekValue) {
        var member=access.student(actor);LocalDate start=parseWeek(weekValue);
        var rows=db.queryForList("select id from weekly_report where user_id=? and school_id=? and week_start=? and deleted_at is null",actor,member.get("school_id"),start);
        return rows.isEmpty()?null:own(actor,((Number)rows.getFirst().get("id")).longValue());
    }
    public Map<String,Object> save(long actor,Draft input,boolean submit) {
        var member=access.student(actor); long school=((Number)member.get("school_id")).longValue();
        // Same order as cycle generation/delivery: settings first, then user and report.
        db.update("insert into weekly_report_setting(school_id) values (?) on conflict do nothing",school);
        db.queryForList("select school_id from weekly_report_setting where school_id=? for share",school);
        db.queryForList("select id from app_user where id=? for no key update",actor);
        member=access.student(actor);
        if(((Number)member.get("school_id")).longValue()!=school) throw ApiException.conflict("学校身份已变化，请重新打开周报");
        Instant now=clock.now(); LocalDate start=parseWeek(input.weekStart());
        Instant joined=instant(member.get("reviewed_at")!=null?member.get("reviewed_at"):member.get("applied_at"));
        if(start.isAfter(week(now)) || !TimeSupport.campusDayStart(start.plusWeeks(1)).isAfter(joined))
            throw ApiException.validation("不能创建未来或加入学校之前的周报");
        String summary=text(input.summary(),10000),problems=text(input.problems(),5000),plan=text(input.plan(),5000);
        if(submit && summary.isBlank()) throw ApiException.validation("请填写本周学习总结");
        List<Map<String,Object>> rows=input.id()==null
            ?db.queryForList("select * from weekly_report where school_id=? and user_id=? and week_start=? for update",school,actor,start)
            :db.queryForList("select * from weekly_report where id=? and school_id=? and user_id=? and week_start=? for update",input.id(),school,actor,start);
        long id;
        if(rows.isEmpty()) {
            if(input.id()!=null) throw ApiException.notFound("周报不存在或学校身份已变化");
            if(input.version()!=0) throw ApiException.conflict("周报版本已变化，请刷新");
            id=db.queryForObject("""
                insert into weekly_report(school_id,user_id,week_start,enrollment_year,created_at,updated_at)
                values (?,?,?,?,?,?) returning id
                """,Long.class,school,actor,start,member.get("enrollment_year"),sql(now),sql(now));
        } else {
            var row=rows.getFirst();id=((Number)row.get("id")).longValue();
            if(row.get("deleted_at")!=null) {
                if(input.id()!=null || input.version()!=0) throw ApiException.conflict("草稿已删除，请重新创建");
            } else version(row,input.version());
            if(!List.of("DRAFT","RETURNED").contains(row.get("status"))) throw ApiException.conflict("已提交周报须由负责人退回后修改");
        }
        db.update("""
            update weekly_report set summary=?,problems=?,plan=?,deleted_at=null,status=case when ? then 'SUBMITTED' else status end,
            enrollment_year=case when submitted_at is null then ? else enrollment_year end,
            submitted_at=case when ? then ? else submitted_at end,updated_at=?,version=version+1 where id=?
            """,summary,problems,plan,submit,member.get("enrollment_year"),submit,sql(now),sql(now),id);
        event(id,actor,submit?"SUBMIT":"SAVE","",now);
        return own(actor,id);
    }
    public void delete(long actor,long id,int version) {
        var located=db.queryForList("select school_id from weekly_report where id=? and user_id=? and deleted_at is null",id,actor);
        if(located.isEmpty()) throw ApiException.notFound("草稿不存在");
        db.queryForList("select school_id from weekly_report_setting where school_id=? for share",located.getFirst().get("school_id"));
        db.queryForList("select id from app_user where id=? for no key update",actor);
        var rows=db.queryForList("select * from weekly_report where id=? and user_id=? for update",id,actor);
        if(rows.isEmpty()) throw ApiException.notFound("周报不存在");
        var r=rows.getFirst();version(r,version);
        if(!"DRAFT".equals(r.get("status"))) throw ApiException.conflict("已提交过的周报不能删除");
        Instant now=clock.now();
        if(r.get("deleted_at")!=null) throw ApiException.notFound("草稿已删除");
        db.update("update weekly_report set deleted_at=?,updated_at=?,summary='',problems='',plan='',version=version+1 where id=?",sql(now),sql(now),id);
        // Preserve only the state/timing required for cutoff reconstruction, not deleted draft text.
        db.update("update weekly_report_event set summary='',problems='',plan='' where report_id=? and action='SAVE'",id);
        event(id,actor,"DELETE","",now);
    }
    static void checkPage(int page) { if(page<1 || page>100000) throw ApiException.validation("页码无效"); }
    static void version(Map<String,Object> row,int version) {
        if(((Number)row.get("version")).intValue()!=version) throw ApiException.conflict("内容已被处理或修改，请刷新后重试");
    }
    private void event(long id,long actor,String action,String comment,Instant now) {
        db.update("""
            insert into weekly_report_event(report_id,version,status,action,summary,problems,plan,comment,actor_id,happened_at,submitted_at)
            select id,version,case when ?='DELETE' then 'NOT_CREATED' else status end,?,summary,problems,plan,?,?,?,submitted_at from weekly_report where id=?
            """,action,action,comment,actor,sql(now),id);
    }
    private Map<String,Object> detail(Map<String,Object> r,boolean owner) {
        long id=((Number)r.get("id")).longValue();
        var out=new LinkedHashMap<String,Object>();
        out.put("id",id);out.put("schoolId",r.get("school_id"));out.put("userId",r.get("user_id"));
        out.put("weekStart",r.get("week_start"));out.put("enrollmentYear",r.get("enrollment_year"));
        out.put("status",r.get("status"));out.put("version",r.get("version"));out.put("submittedAt",r.get("submitted_at"));
        var versions=db.queryForList("""
            select e.version,e.status,e.action,e.summary,e.problems,e.plan,e.comment,e.happened_at as "happenedAt",
            u.display_name as "actorName" from weekly_report_event e left join app_user u on u.id=e.actor_id
            where e.report_id=? and (? or e.action in ('SUBMIT','REVIEW','RETURN')) order by e.version desc limit 100
            """,id,owner);
        out.put("history",versions);
        if(owner) { out.put("summary",r.get("summary"));out.put("problems",r.get("problems"));out.put("plan",r.get("plan")); }
        else if(!versions.isEmpty()) {
            for(String field:List.of("summary","problems","plan")) out.put(field,versions.getFirst().get(field));
        }
        return out;
    }
    public Map<String,Object> managed(long actor,long school,Integer year,String weekValue,String status,String q,int page) {
        access.scope(actor,school,year);checkPage(page);LocalDate start=parseWeek(weekValue);
        if(!List.of("","SUBMITTED","REVIEWED","RETURNED").contains(status) || q.length()>100) throw ApiException.validation("筛选参数无效");
        String where="""
            from weekly_report r join app_user u on u.id=r.user_id
            left join school_member m on m.school_id=r.school_id and m.user_id=r.user_id
            where r.school_id=? and r.week_start=? and r.submitted_at is not null
            and (?::integer is null or r.enrollment_year=?) and (?='' or r.status=?)
            and position(lower(?) in lower(u.display_name||' '||u.username||' '||coalesce(m.student_no,'')))>0
            """;
        Object[] args={school,start,year,year,status,status,q};
        long total=db.queryForObject("select count(*) "+where,Long.class,args);
        var params=new ArrayList<Object>(Arrays.asList(args));params.add((page-1)*20);
        return Map.of("total",total,"rows",db.queryForList("""
            select r.id,r.user_id as "userId",u.display_name as "displayName",u.username,r.enrollment_year as "enrollmentYear",
            m.student_no as "studentNo",r.status,r.version,r.submitted_at as "submittedAt"
            """+where+" order by r.id desc limit 20 offset ?",params.toArray()));
    }
    public Map<String,Object> managedDetail(long actor,long school,Integer year,long id) {
        access.scope(actor,school,year);
        return detail(managedRow(school,year,id,false),false);
    }
    private Map<String,Object> managedRow(long school,Integer year,long id,boolean lock) {
        var rows=db.queryForList("select * from weekly_report where id=? and school_id=? and submitted_at is not null and (?::integer is null or enrollment_year=?)"+(lock?" for update":""),id,school,year,year);
        if(rows.isEmpty()) throw ApiException.notFound("本管理范围内没有该已提交周报");
        return rows.getFirst();
    }
    public Map<String,Object> decide(long actor,long school,Integer year,long id,Decision input) {
        db.queryForList("select school_id from weekly_report_setting where school_id=? for share",school);
        access.scope(actor,school,year);
        var row=managedRow(school,year,id,true);version(row,input.version());
        if(((Number)row.get("user_id")).longValue()==actor) throw ApiException.forbidden("不能评价或退回自己的周报");
        if(!(input.returned()?List.of("SUBMITTED","REVIEWED"):List.of("SUBMITTED")).contains(row.get("status")))
            throw ApiException.conflict("周报已被处理，请刷新");
        String comment=text(input.comment(),3000);if(comment.isBlank()) throw ApiException.validation("请填写评价或退回原因");
        db.update("update weekly_report set status=?,version=version+1 where id=?",input.returned()?"RETURNED":"REVIEWED",id);
        Instant now=clock.now();event(id,actor,input.returned()?"RETURN":"REVIEW",comment,now);
        access.audit(actor,input.returned()?"WEEKLY_REPORT_RETURN":"WEEKLY_REPORT_REVIEW","school="+school+",report="+id);
        return detail(managedRow(school,year,id,false),false);
    }
}
