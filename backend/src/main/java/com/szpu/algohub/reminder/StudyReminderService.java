package com.szpu.algohub.reminder;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.common.ApiException;
import com.szpu.algohub.common.TimeSupport;

@Service
public class StudyReminderService {
    private final JdbcTemplate jdbc;
    public StudyReminderService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record PersonalRequest(Integer weekly, Integer monthly, boolean emailEnabled) {}
    public record AssignmentRequest(Integer weekly, Integer monthly) {}
    public record ManualRequest(String message, UUID requestId) {}
    public record Goal(Integer personal, int required, int target, int solved, String source) {}

    private Map<String,Object> settings(long userId) {
        var rows = jdbc.queryForList("""
            select s.personal_weekly, s.personal_monthly, s.assigned_weekly, s.assigned_monthly,
                   coalesce(s.email_enabled,true) email_enabled, m.enrollment_year,
                   coalesce(d.monthly_target,0) default_monthly, e.email
            from app_user u
            left join study_goal_setting s on s.user_id=u.id
            left join school_member m on m.user_id=u.id and m.status='APPROVED'
            left join study_goal_default d on d.enrollment_year=m.enrollment_year
            left join verified_login_email e on e.user_id=u.id
            where u.id=? and u.status='ACTIVE'
            """,userId);
        if(rows.isEmpty()) throw ApiException.notFound("用户不存在或已停用");
        return rows.getFirst();
    }

    public Map<String,Object> current(long userId) { return current(userId,TimeSupport.today()); }
    public Map<String,Object> current(long userId, LocalDate date) {
        var s=settings(userId);
        LocalDate week=date.with(DayOfWeek.MONDAY), month=date.withDayOfMonth(1);
        var result=new LinkedHashMap<String,Object>();
        result.put("weekly",goal(s,"weekly",solved(userId,week,week.plusWeeks(1))));
        result.put("monthly",goal(s,"monthly",solved(userId,month,month.plusMonths(1))));
        result.put("enrollmentYear",s.get("enrollment_year"));
        result.put("assignedWeekly",s.get("assigned_weekly"));
        result.put("assignedMonthly",s.get("assigned_monthly"));
        result.put("emailEnabled",s.get("email_enabled"));
        result.put("email",s.get("email"));
        result.put("weekStart",week); result.put("monthStart",month);
        return result;
    }

    private Goal goal(Map<String,Object> s,String period,int solved) {
        Integer personal=(Integer)s.get("personal_"+period), assigned=(Integer)s.get("assigned_"+period);
        int required=assigned!=null?assigned:period.equals("monthly")?(Integer)s.get("default_monthly"):0;
        String source=assigned!=null?"ADMIN":period.equals("monthly")&&required>0?"GRADE":"NONE";
        return new Goal(personal,required,Math.max(required,personal==null?0:personal),solved,source);
    }

    /** 先合并同一平台同题在所有账号上的首次通过，再按周期筛选，避免重复账号把旧题算作新题。 */
    public int solved(long userId,LocalDate from,LocalDate until) {
        return jdbc.queryForObject("""
            select count(*) from (
                select s.platform,s.problem_id,min(s.earliest_known_ac_at) ac_at,
                       bool_and(s.first_ac_confidence='EXACT' and s.earliest_known_ac_at is not null) exact
                from user_solved_problem s join platform_account a on a.id=s.account_id and a.user_id=s.user_id
                where s.user_id=? and a.ownership_status in ('RESOLVED','VERIFIED')
                group by s.platform,s.problem_id
            ) first_ac where exact and ac_at>=? and ac_at<?
            """,Integer.class,userId,TimeSupport.campusDayStart(from).atOffset(ZoneOffset.UTC),
            TimeSupport.campusDayStart(until).atOffset(ZoneOffset.UTC));
    }

    private void validate(Integer n,boolean allowZero) {
        if(n!=null && (n<(allowZero?0:1)||n>10000)) throw ApiException.validation("目标题数须在 "+(allowZero?0:1)+"～10000 之间");
    }

    @Transactional
    public Map<String,Object> save(long userId,PersonalRequest request) {
        validate(request.weekly(),false);validate(request.monthly(),false);
        lockActiveUser(userId);
        settings(userId);
        jdbc.update("""
            insert into study_goal_setting(user_id,personal_weekly,personal_monthly,email_enabled) values(?,?,?,?)
            on conflict(user_id) do update set personal_weekly=excluded.personal_weekly,
            personal_monthly=excluded.personal_monthly,email_enabled=excluded.email_enabled,updated_at=now()
            """,userId,request.weekly(),request.monthly(),request.emailEnabled());
        return current(userId);
    }

    @Transactional
    public Map<String,Object> assign(long actor,long userId,AssignmentRequest request) {
        validate(request.weekly(),true);validate(request.monthly(),true);
        lockActiveUser(userId);
        var previous=settings(userId);
        if(Objects.equals(previous.get("assigned_weekly"),request.weekly())
            && Objects.equals(previous.get("assigned_monthly"),request.monthly())) return current(userId);
        jdbc.update("""
            insert into study_goal_setting(user_id,assigned_weekly,assigned_monthly) values(?,?,?)
            on conflict(user_id) do update set assigned_weekly=excluded.assigned_weekly,
            assigned_monthly=excluded.assigned_monthly,updated_at=now()
            """,userId,request.weekly(),request.monthly());
        Long audit=jdbc.queryForObject("insert into study_goal_audit(user_id,actor_id,weekly,monthly) values(?,?,?,?) returning id",
            Long.class,userId,actor,request.weekly(),request.monthly());
        var state=current(userId);
        Goal week=(Goal)state.get("weekly"),month=(Goal)state.get("monthly");
        enqueue(userId,"ASSIGNMENT","assignment:"+audit,"学习目标已调整",
            "管理员调整了你的学习要求：每周 "+week.required()+" 题，每月 "+month.required()+" 题。\n"
            +"个人计划高于学校要求时，按个人计划提醒。可在「目标与提醒」查看。",actor);
        return state;
    }

    private void lockActiveUser(long userId) {
        if(jdbc.queryForList("select id from app_user where id=? and status='ACTIVE' for update",userId).isEmpty())
            throw ApiException.notFound("用户不存在或已停用");
    }

    @Transactional
    public Map<String,Object> manual(long actor,long userId,ManualRequest request) {
        if(request.requestId()==null) throw ApiException.validation("缺少请求标识");
        String message=request.message()==null?"":request.message().strip();
        if(message.length()>1000) throw ApiException.validation("提醒内容最多 1000 字");
        lockActiveUser(userId);
        String key="manual:"+request.requestId();
        var previous=jdbc.queryForList("select id from study_notification where user_id=? and dedup_key=?",userId,key);
        if(!previous.isEmpty()) return Map.of("id",previous.getFirst().get("id"));
        if(jdbc.queryForObject("select count(*) from study_notification where user_id=? and kind='MANUAL' and created_at>now()-interval '60 seconds'",Integer.class,userId)>0)
            throw ApiException.validation("该用户刚收到提醒，请 60 秒后再发送");
        var s=current(userId);
        String body=progress("本周",(Goal)s.get("weekly"))+"\n"+progress("本月",(Goal)s.get("monthly"));
        if(!message.isBlank()) body+="\n\n管理员留言："+message;
        return Map.of("id",enqueue(userId,"MANUAL",key,"管理员学习提醒",body,actor));
    }

    private String progress(String label,Goal g) {
        return label+"新通过 "+g.solved()+" 题"+(g.target()>0?"，目标 "+g.target()+" 题，还差 "+Math.max(0,g.target()-g.solved())+" 题。":"，尚未设置目标。");
    }

    /** Must run inside the caller's transaction, so a notification cannot lose its email outbox. */
    private long enqueue(long userId,String kind,String key,String title,String body,Long actor) {
        var ids=jdbc.queryForList("""
            insert into study_notification(user_id,kind,dedup_key,title,body,created_by) values(?,?,?,?,?,?)
            on conflict(user_id,dedup_key) do nothing returning id
            """,Long.class,userId,kind,key,title,body+"\n\n统计按北京时间，仅计有准确首次通过时间的题。同平台同题只算一次，数据以最近同步为准。",actor);
        if(ids.isEmpty()) return 0;
        long id=ids.getFirst();
        jdbc.update("""
            insert into study_mail_outbox(notification_id,status,last_error)
            select ?,case when coalesce(s.email_enabled,true) then 'PENDING' else 'SKIPPED' end,
            case when coalesce(s.email_enabled,true) then null else '用户已关闭邮件提醒' end
            from app_user u left join study_goal_setting s on s.user_id=u.id where u.id=?
            """,id,userId);
        return id;
    }

    private static void checkCategory(String category) {
        if(!List.of("ALL","SYSTEM","FRIEND").contains(category)) throw ApiException.validation("消息分类无效");
    }
    public Map<String,Object> inbox(long userId,long before) { return inbox(userId,before,"ALL"); }
    public Map<String,Object> inbox(long userId,long before,String category) {
        checkCategory(category);
        var items=jdbc.queryForList("""
            select n.id,n.kind,n.title,n.body,n.category,n.created_by as "senderId",u.display_name as "senderName",
                   n.created_at as "createdAt",n.read_at as "readAt",o.status as "emailStatus",o.last_error as "emailNote"
            from study_notification n left join study_mail_outbox o on o.notification_id=n.id
            left join app_user u on u.id=n.created_by
            where n.user_id=? and n.id<? and (?='ALL' or n.category=?) order by n.id desc limit 21
            """,userId,before<=0?Long.MAX_VALUE:before,category,category);
        boolean more=items.size()>20;
        var counts=jdbc.queryForMap("""
            select count(*) filter(where category='SYSTEM') as "SYSTEM",count(*) filter(where category='FRIEND') as "FRIEND"
            from study_notification where user_id=? and read_at is null
            """,userId);
        return Map.of("items",more?items.subList(0,20):items,"hasMore",more,"unread",unread(userId),"unreadByCategory",counts);
    }
    public int unread(long userId) {
        return jdbc.queryForObject("select count(*) from study_notification where user_id=? and read_at is null",Integer.class,userId);
    }
    public void read(long userId,long id) {
        if(jdbc.update("update study_notification set read_at=coalesce(read_at,now()) where id=? and user_id=?",id,userId)==0)
            throw ApiException.notFound("消息不存在");
    }
    public void readAll(long userId) { readAll(userId,"ALL"); }
    public void readAll(long userId,String category) {
        checkCategory(category);
        jdbc.update("update study_notification set read_at=now() where user_id=? and read_at is null and (?='ALL' or category=?)",userId,category,category);
    }

    public List<Map<String,Object>> members(String search,long after) {
        String term=search==null?"":search.strip();
        if(term.length()>80) throw ApiException.validation("搜索内容过长");
        return jdbc.queryForList("""
            select u.id,u.username,u.display_name as "displayName",m.enrollment_year as "enrollmentYear",
            m.class_name as "className",s.name as "schoolName"
            from app_user u join school_member m on m.user_id=u.id and m.status='APPROVED'
            join school s on s.id=m.school_id
            where u.status='ACTIVE' and u.id>? and (strpos(lower(u.username),lower(?))>0
            or strpos(lower(coalesce(u.display_name,'')),lower(?))>0 or cast(m.enrollment_year as text)=?)
            order by u.id limit 50
            """,after,term,term,term);
    }

    public List<Map<String,Object>> deliveries(long userId) {
        return jdbc.queryForList("""
            select n.id,n.title,n.created_at as "createdAt",n.read_at as "readAt",
                   o.status,o.attempts,o.last_error as "lastError",o.sent_at as "sentAt"
            from study_notification n join study_mail_outbox o on o.notification_id=n.id
            where n.user_id=? order by n.id desc limit 20
            """,userId);
    }

    /** The scheduler repeatedly picks undelivered users in bounded batches; the unique key also fences multiple nodes. */
    public List<Long> dueUsers(String kind,LocalDate date) {
        return jdbc.queryForList("""
            select u.id from app_user u join school_member m on m.user_id=u.id and m.status='APPROVED'
            where u.status='ACTIVE' and u.role='STUDENT' and not exists(
            select 1 from study_notification n where n.user_id=u.id and n.dedup_key=?)
            order by u.id limit 100
            """,Long.class,kind+":"+date);
    }

    @Transactional
    public void scheduled(long userId,String kind,LocalDate date) {
        lockActiveUser(userId);
        var state=current(userId,date);
        String title,body;
        if(kind.equals("REPORT")) {
            LocalDate from=date.with(DayOfWeek.MONDAY).minusWeeks(1);
            int total=solved(userId,from,from.plusWeeks(1));
            title="每周学习报告";
            body="上周（"+from+" 至 "+from.plusDays(6)+"）新通过 "+total+" 题。\n"
                +progress("本周",(Goal)state.get("weekly"))+"\n"+progress("本月",(Goal)state.get("monthly"));
        } else {
            boolean weekly=kind.equals("WEEKLY");
            Goal g=(Goal)state.get(weekly?"weekly":"monthly");
            title=weekly?"周四学习提醒":"月中学习提醒";
            body=progress(weekly?"本周":"本月",g);
            if(g.target()>0 && g.solved()>=g.target()) body+="\n已达成本期目标，继续保持！";
            else if(g.target()==0) body+="\n可以在「目标与提醒」设置自己的持续计划。";
        }
        enqueue(userId,kind,kind+":"+date,title,body,null);
    }
}
