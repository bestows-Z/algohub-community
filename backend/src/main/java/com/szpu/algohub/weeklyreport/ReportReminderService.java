package com.szpu.algohub.weeklyreport;

import static com.szpu.algohub.weeklyreport.ReportTime.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.szpu.algohub.mail.BrandedMail;

@Service
@Transactional
public class ReportReminderService {
    private final JdbcTemplate db;
    private final ReportCycleService cycles;
    private final ReportAccess access;
    private final ObjectProvider<JavaMailSender> sender;
    private final String from,origin;
    private final boolean enabled;
    private final ReportTime clock;
    public ReportReminderService(JdbcTemplate db,ReportCycleService cycles,ReportAccess access,ObjectProvider<JavaMailSender> sender,
        @Value("${algohub.email.from:}") String from,@Value("${PUBLIC_ORIGIN:}") String origin,
        @Value("${algohub.reminders.enabled:false}") boolean enabled,ReportTime clock) {
        this.db=db;this.cycles=cycles;this.access=access;this.sender=sender;this.from=from;this.origin=origin;this.enabled=enabled;this.clock=clock;
    }
    public Map<String,Object> status() { return Map.of("automaticRemindersEnabled",enabled,"mailConfigured",sender.getIfAvailable()!=null && !from.isBlank() && !origin.isBlank()); }
    public void generate(long cycleId,Instant now) {
        var c=db.queryForMap("select * from weekly_report_cycle where id=?",cycleId);
        long school=((Number)c.get("school_id")).longValue();
        var setting=db.queryForMap("select * from weekly_report_setting where school_id=? for update",school);
        c=db.queryForMap("select * from weekly_report_cycle where id=? for update",cycleId);
        if(!Boolean.TRUE.equals(setting.get("enabled")) || Boolean.TRUE.equals(c.get("reminder_done")) || c.get("remind_at")==null) return;
        Instant at=instant(c.get("remind_at")),cutoff=instant(c.get("cutoff_at"));
        if(now.isBefore(at))return;
        if(!now.isBefore(cutoff) || at.isBefore(instant(setting.get("enabled_since")))) {
            db.update("update weekly_report_cycle set reminder_done=true where id=?",cycleId);return;
        }
        LocalDate start=((java.sql.Date)c.get("week_start")).toLocalDate();
        for(var r:cycles.roster(school,start,at)) {
            if(List.of("SUBMITTED","REVIEWED").contains(r.get("cutoff_status")))continue;
            long user=((Number)r.get("user_id")).longValue();
            String title="周报提交提醒",body=r.get("display_name")+"，你好：\n"+start+" 至 "+start.plusDays(6)+" 的周报尚未提交。\n提交截止统计时间："
                +cutoff.atZone(com.szpu.algohub.common.TimeSupport.CAMPUS_ZONE).toLocalDateTime().toString().replace('T',' ')+"（北京时间）。\n请尽快提交。\n周报入口："+(origin.isBlank()?"/weekly-reports":origin+"/weekly-reports");
            var ids=db.queryForList("""
                insert into study_notification(user_id,kind,dedup_key,title,body) values (?,'WEEKLY_REPORT_DUE',?,?,?)
                on conflict(user_id,dedup_key) do nothing returning id
                """,Long.class,user,"weekly-report:"+school+":"+start,title,body);
            if(ids.isEmpty())continue;long notification=ids.getFirst();
            db.update("insert into weekly_report_reminder(cycle_id,user_id,enrollment_year,notification_id) values (?,?,?,?)",cycleId,user,r.get("enrollment_year"),notification);
            String reason=Boolean.TRUE.equals(c.get("email_enabled"))?reason(user,school,start):"学校本周已关闭邮件提醒";
            db.update("insert into study_mail_outbox(notification_id,status,last_error) values (?,?,?)",notification,reason==null?"PENDING":"SKIPPED",reason);
        }
        db.update("update weekly_report_cycle set reminder_done=true where id=?",cycleId);
    }
    private String reason(long user,long school,LocalDate start) {
        var users=db.queryForList("""
            select u.status,u.role,coalesce(g.email_enabled,true) email_enabled,e.email,
            exists(select 1 from school_member m where m.user_id=u.id and m.school_id=? and m.status='APPROVED') member,
            exists(select 1 from school_administrator a where a.user_id=u.id) school_admin
            from app_user u left join study_goal_setting g on g.user_id=u.id left join verified_login_email e on e.user_id=u.id where u.id=?
            """,school,user);
        if(users.isEmpty())return "账号不再符合接收条件";
        var row=users.getFirst();
        if(!"ACTIVE".equals(row.get("status")) || !"STUDENT".equals(row.get("role")) || !Boolean.TRUE.equals(row.get("member")) || Boolean.TRUE.equals(row.get("school_admin")))return "学校成员身份或账号已失效";
        if(Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from weekly_report where school_id=? and user_id=? and week_start=? and status in ('SUBMITTED','REVIEWED'))",Boolean.class,school,user,start)))return "周报已提交，取消邮件";
        if(!Boolean.TRUE.equals(row.get("email_enabled")))return "个人已关闭邮件提醒";
        if(row.get("email")==null)return "无已验证邮箱";
        return null;
    }
    /** Settings lock remains held through send: disable waits for in-flight sends and cancels all remaining ones. */
    public boolean deliverOne(long school,Instant now) {
        var setting=db.queryForList("select enabled from weekly_report_setting where school_id=? for update",school);
        if(setting.isEmpty() || !Boolean.TRUE.equals(setting.getFirst().get("enabled")))return false;
        now=clock.now();
        var rows=db.queryForList("""
            select o.*,n.user_id,c.week_start,c.cutoff_at,c.email_enabled,s.title,s.body
            from study_mail_outbox o join weekly_report_reminder n on n.notification_id=o.notification_id
            join weekly_report_cycle c on c.id=n.cycle_id join study_notification s on s.id=n.notification_id
            where c.school_id=? and ((o.status='PENDING' and o.available_at<=?) or (o.status='SENDING' and o.lease_until<?))
            order by o.available_at,o.notification_id limit 1
            """,school,sql(now),sql(now));
        if(rows.isEmpty())return false;
        var row=rows.getFirst();long id=((Number)row.get("notification_id")).longValue(),user=((Number)row.get("user_id")).longValue();
        // Account deletion locks the user before cascading into notifications/outbox.
        // Match that order, and recheck the unlocked candidate after acquiring both rows.
        if(db.queryForList("select id from app_user where id=? for share",user).isEmpty())return true;
        now=clock.now();
        var pending=db.queryForList("select * from study_mail_outbox where notification_id=? and ((status='PENDING' and available_at<=?) or (status='SENDING' and lease_until<?)) for update",id,sql(now),sql(now));
        if(pending.isEmpty())return true;
        row.putAll(pending.getFirst());
        String reason=!now.isBefore(instant(row.get("cutoff_at")))?"提前提醒已过期":reason(user,school,((java.sql.Date)row.get("week_start")).toLocalDate());
        if(!Boolean.TRUE.equals(row.get("email_enabled")))reason="学校本周已关闭邮件提醒";
        int attempts=((Number)row.get("attempts")).intValue();
        if(reason!=null) { finish(id,"SKIPPED",reason,null);return true; }
        if(attempts>=5) {finish(id,"FAILED","邮件发送失败，已达到重试上限",null);return true;}
        JavaMailSender mail=sender.getIfAvailable();
        if(mail==null || from.isBlank() || origin.isBlank()) {
            db.update("update study_mail_outbox set last_error='邮件服务或周报入口未配置',available_at=? where notification_id=?",sql(now.plusSeconds(60)),id);return false;
        }
        db.update("update study_mail_outbox set status='SENDING',attempts=attempts+1,lease_until=? where notification_id=?",sql(now.plusSeconds(600)),id);
        try {
            String email=db.queryForObject("select email from verified_login_email where user_id=?",String.class,user);
            BrandedMail.sendNotice(mail,from,email,"AlgoHub · "+row.get("title"),(String)row.get("title"),(String)row.get("body"),"可在「目标与提醒」关闭邮件接收，站内提醒仍保留。邮件已发出不代表已阅读。");
            finish(id,"SENT",null,clock.now());
        } catch(Exception e) {
            db.update("update study_mail_outbox set status=?,last_error='邮件服务发送失败',lease_until=null,available_at=? where notification_id=?",attempts+1>=5?"FAILED":"PENDING",sql(now.plusSeconds((attempts+1)*300L)),id);
        }
        return true;
    }
    private void finish(long id,String state,String reason,Instant at) {
        db.update("update study_mail_outbox set status=?,last_error=?,sent_at=?,lease_until=null,lease_token=null where notification_id=?",state,reason,sql(at),id);
    }
    public List<Map<String,Object>> overview(long actor,long school,String startValue) {
        access.manager(actor,school);LocalDate start=parseWeek(startValue);
        return db.queryForList("""
            select o.status,o.last_error as reason,count(*) as count from weekly_report_reminder n
            join weekly_report_cycle c on c.id=n.cycle_id join study_mail_outbox o on o.notification_id=n.notification_id
            where c.school_id=? and c.week_start=? group by o.status,o.last_error order by o.status,o.last_error
            """,school,start);
    }
}
