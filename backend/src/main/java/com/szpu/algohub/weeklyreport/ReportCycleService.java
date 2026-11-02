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
public class ReportCycleService {
    private final JdbcTemplate db;
    private final ReportAccess access;
    public ReportCycleService(JdbcTemplate db,ReportAccess access) { this.db=db;this.access=access; }
    public Map<String,Object> rule(long school,LocalDate week) {
        var rows=db.queryForList("select * from weekly_report_rule where school_id=? and effective_week<=? order by effective_week desc limit 1",school,week);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Instant cutoff(LocalDate start,Map<String,Object> rule) {
        return start.plusDays(((Number)rule.get("day_of_week")).intValue()-1)
            .atStartOfDay(TimeSupport.CAMPUS_ZONE).plusMinutes(((Number)rule.get("minute_of_day")).intValue()).toInstant();
    }
    public void ensureCycle(long school,LocalDate start,Instant now) {
        var rows=db.queryForList("select * from weekly_report_setting where school_id=? for update",school);
        if(rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("enabled"))) return;
        var rule=rule(school,start);if(rule==null)return;
        Instant cutoff=cutoff(start,rule),since=instant(rows.getFirst().get("enabled_since"));
        if(cutoff.isBefore(since)) return; // Never backfill times missed while disabled.
        int lead=((Number)rule.get("lead_hours")).intValue();Instant remind=lead==0?null:cutoff.minusSeconds(lead*3600L);
        db.update("""
            insert into weekly_report_cycle(school_id,week_start,cutoff_at,remind_at,email_enabled,reminder_done)
            values (?,?,?,?,?,?) on conflict(school_id,week_start) do nothing
            """,school,start,sql(cutoff),sql(remind),rule.get("email_enabled"),remind==null || remind.isBefore(since));
        if(cutoff.isAfter(now)) db.update("update weekly_report_cycle set status='PENDING',error=null where school_id=? and week_start=? and status='CANCELLED'",school,start);
    }
    /** Latest historical eligibility per membership, then latest report state at the actual cutoff. */
    public List<Map<String,Object>> roster(long school,LocalDate start,Instant at) {
        return db.queryForList("""
            with roster as (
                select distinct on(member_id) * from weekly_report_roster_history
                where school_id=? and happened_at<=? order by member_id,happened_at desc,id desc
            )
            select h.user_id,h.enrollment_year,h.class_name,h.student_no,h.display_name,h.username,
                coalesce(e.status,'NOT_CREATED') as cutoff_status,
                (select max(edit.happened_at) from weekly_report_event edit where edit.report_id=r.id
                    and edit.action in ('SAVE','SUBMIT') and edit.happened_at<=?) as last_edited_at
            from roster h left join weekly_report r on r.user_id=h.user_id and r.school_id=h.school_id and r.week_start=?
            left join lateral(select status,happened_at from weekly_report_event where report_id=r.id and happened_at<=?
                order by happened_at desc,id desc limit 1) e on true
            where h.eligible
            """,school,sql(at),sql(at),start,sql(at));
    }
    public void snapshot(long cycleId,Instant now) {
        var rows=db.queryForList("select * from weekly_report_cycle where id=?",cycleId);if(rows.isEmpty())return;
        var cycle=rows.getFirst();long school=((Number)cycle.get("school_id")).longValue();
        var settings=db.queryForList("select enabled from weekly_report_setting where school_id=? for update",school);
        cycle=db.queryForMap("select * from weekly_report_cycle where id=? for update",cycleId);
        if(!Boolean.TRUE.equals(settings.getFirst().get("enabled")) || !List.of("PENDING","FAILED").contains(cycle.get("status")))return;
        Instant cutoff=instant(cycle.get("cutoff_at"));if(now.isBefore(cutoff))return;
        LocalDate start=((java.sql.Date)cycle.get("week_start")).toLocalDate();
        // Existing member/account paths lock users before writing roster history. Drain those
        // transactions before reading it; use the same administrator-first order as legacy paths.
        var lockedUsers=db.queryForList("""
            select u.id from app_user u where exists(select 1 from school_member m where m.user_id=u.id and m.school_id=?)
            order by case when u.role='ADMIN' then 0 else 1 end,u.id for share
            """,Long.class,school);
        // New memberships hold a school FK key-share lock. Acquire the school only after users,
        // so an in-flight member update can finish its history FK writes without a reverse lock.
        db.queryForList("select id from school where id=? for update",school);
        var currentUsers=db.queryForList("select distinct user_id from school_member where school_id=?",Long.class,school);
        if(!new HashSet<>(lockedUsers).containsAll(currentUsers))
            throw ApiException.conflict("成员范围刚刚变化，统计将自动重试");
        var roster=roster(school,start,cutoff);int submitted=0;
        for(var r:roster) {
            if(List.of("SUBMITTED","REVIEWED").contains(r.get("cutoff_status"))) { submitted++;continue; }
            var mail=db.queryForList("""
                select h.status,h.note,h.sent_at from weekly_report_reminder n join weekly_report_mail_event h on h.notification_id=n.notification_id
                where n.cycle_id=? and n.user_id=? and h.happened_at<=? order by h.happened_at desc,h.id desc limit 1
                """,cycleId,r.get("user_id"),sql(cutoff));
            var result=mail.isEmpty()?Collections.<String,Object>emptyMap():mail.getFirst();
            db.update("""
                insert into weekly_report_missing(cycle_id,user_id,enrollment_year,class_name,student_no,display_name,username,cutoff_status,last_edited_at,reminder_status,reminder_note,reminder_sent_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?) on conflict(cycle_id,user_id) do nothing
                """,cycleId,r.get("user_id"),r.get("enrollment_year"),r.get("class_name"),r.get("student_no"),r.get("display_name"),r.get("username"),r.get("cutoff_status"),r.get("last_edited_at"),result.get("status"),result.get("note"),result.get("sent_at"));
        }
        db.update("update weekly_report_cycle set status='COMPLETE',error=null,generated_at=?,eligible_count=?,submitted_count=? where id=?",sql(now),roster.size(),submitted,cycleId);
    }
    public void failed(long cycleId) {
        db.update("update weekly_report_cycle set status='FAILED',error='统计暂未完成，系统将重试' where id=? and status in ('PENDING','FAILED')",cycleId);
    }
    public boolean listAccess(long actor,long school,Integer year) {
        boolean manager=access.scope(actor,school,year);
        if(!manager && !Boolean.TRUE.equals(db.queryForObject("select coalesce((select enabled from weekly_report_setting where school_id=?),false)",Boolean.class,school)))
            throw ApiException.forbidden("学校未开启周报统计，年级名单暂不可查看");
        return manager;
    }
    public List<Map<String,Object>> cycles(long actor,long school,Integer year) {
        boolean manager=listAccess(actor,school,year);
        // Captains get only their own scope's missing count; whole-school totals never leave the server.
        return db.queryForList("""
            select c.id,c.week_start as "weekStart",c.cutoff_at as "cutoffAt",c.generated_at as "generatedAt",c.status,c.error,
            case when ? then c.eligible_count end as "eligibleCount",case when ? then c.submitted_count end as "submittedCount",
            case when c.status='COMPLETE' then (select count(*) from weekly_report_missing m where m.cycle_id=c.id
                and (?::integer is null or m.enrollment_year=?) and (? or m.user_id<>?)) end as "missingCount"
            from weekly_report_cycle c where c.school_id=? order by c.week_start desc limit 104
            """,manager,manager,year,year,manager,actor,school);
    }
    public Map<String,Object> missing(long actor,long school,Integer year,String startValue,String className,String q,String late,String reminder,int page) {
        boolean manager=listAccess(actor,school,year);WeeklyReportService.checkPage(page);LocalDate start=parseWeek(startValue);
        if(q.length()>100 || className.length()>100 || !List.of("","YES","NO").contains(late) || !List.of("","SENT","PENDING","SENDING","FAILED","SKIPPED","NONE").contains(reminder)) throw ApiException.validation("筛选参数无效");
        String where="""
            from weekly_report_missing m join weekly_report_cycle c on c.id=m.cycle_id
            left join weekly_report r on r.school_id=c.school_id and r.user_id=m.user_id and r.week_start=c.week_start
            left join lateral(select min(e.happened_at) as at from weekly_report_event e where e.report_id=r.id
                and e.action='SUBMIT' and e.happened_at>c.cutoff_at) late on true
            left join weekly_report_reminder n on n.cycle_id=c.id and n.user_id=m.user_id
            left join study_mail_outbox o on o.notification_id=n.notification_id
            where c.school_id=? and c.week_start=? and c.status='COMPLETE' and (?::integer is null or m.enrollment_year=?)
            and (? or m.user_id<>?) and (?='' or coalesce(m.class_name,'')=?)
            and position(lower(?) in lower(m.display_name||' '||m.username||' '||coalesce(m.student_no,'')))>0
            and (?='' or (?='YES' and late.at is not null) or (?='NO' and late.at is null))
            and (?='' or coalesce(m.reminder_status,'NONE')=?)
            """;
        Object[] args={school,start,year,year,manager,actor,className,className,q,late,late,late,reminder,reminder};
        long total=db.queryForObject("select count(*) "+where,Long.class,args);
        var parameters=new ArrayList<Object>(Arrays.asList(args));parameters.add((page-1)*20);
        var rows=db.queryForList("""
            select m.id,m.user_id as "userId",m.enrollment_year as "enrollmentYear",m.class_name as "className",m.student_no as "studentNo",
            m.display_name as "displayName",m.username,m.cutoff_status as "cutoffStatus",m.last_edited_at as "lastEditedAt",
            c.cutoff_at as "cutoffAt",c.generated_at as "generatedAt",r.id as "reportId",case when r.deleted_at is not null then 'NOT_CREATED' else r.status end as "currentStatus",
            r.submitted_at as "submittedAt",late.at as "lateAt",m.reminder_status as "reminderStatus",m.reminder_note as "reminderNote",m.reminder_sent_at as "sentAt",
            o.status as "currentReminderStatus",o.last_error as "currentReminderNote",o.sent_at as "currentSentAt",
            exists(select 1 from verified_login_email e where e.user_id=m.user_id) as "hasVerifiedEmail"
            """+where+" order by m.id limit 20 offset ?",parameters.toArray());
        return Map.of("rows",rows,"total",total);
    }
}
