package com.szpu.algohub.reminder;

import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import com.szpu.algohub.mail.BrandedMail;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StudyReminderJobs {
    private static final Logger log=LoggerFactory.getLogger(StudyReminderJobs.class);
    private final StudyReminderService service;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final boolean enabled;
    public StudyReminderJobs(StudyReminderService service,JdbcTemplate jdbc,ObjectProvider<JavaMailSender> sender,
        @Value("${algohub.email.from:}") String from,@Value("${algohub.reminders.enabled:false}") boolean enabled) {
        this.service=service;this.jdbc=jdbc;this.sender=sender;this.from=from;this.enabled=enabled;
    }
    public Map<String,Object> status() {
        return Map.of("enabled",enabled,"mailConfigured",sender.getIfAvailable()!=null && !from.isBlank());
    }

    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void schedule() {
        if(enabled) generate(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")));
    }
    public void generate(ZonedDateTime now) {
        now=now.withZoneSameInstant(ZoneId.of("Asia/Shanghai"));
        if(now.getHour()<9) return;
        LocalDate date=now.toLocalDate();
        List<String> kinds=new ArrayList<>();
        if(date.getDayOfWeek()==DayOfWeek.MONDAY) kinds.add("REPORT");
        if(date.getDayOfWeek()==DayOfWeek.THURSDAY) kinds.add("WEEKLY");
        if(date.getDayOfMonth()==15) kinds.add("MONTHLY");
        for(String kind:kinds) for(long user:service.dueUsers(kind,date)) {
            try { service.scheduled(user,kind,date); }
            catch(Exception e) { log.warn("学习提醒生成失败 user={} kind={}",user,kind); }
        }
    }

    @Scheduled(fixedDelay=15000,initialDelay=15000)
    public void deliver() { if(enabled) dispatchBatch(); }

    public void dispatchBatch() {
        JavaMailSender mail=sender.getIfAvailable();
        if(mail==null || from.isBlank()) return; // 保留队列，SMTP 配好后继续；UI 明示尚未发送。
        // 有限重试；进程在 SMTP 后崩溃，无法知道对方是否收到，租约到期后可能重发。
        jdbc.update("""
            update study_mail_outbox set status='FAILED',last_error='发送中断次数过多，请管理员检查邮件服务',lease_token=null
            where status='SENDING' and lease_until<now() and attempts>=5
            """);
        for(int i=0;i<20;i++) {
            UUID token=UUID.randomUUID();
            var rows=jdbc.queryForList("""
                with picked as (
                    select notification_id from study_mail_outbox where attempts<5 and
                    not exists(select 1 from weekly_report_reminder wr where wr.notification_id=study_mail_outbox.notification_id) and
                    ((status='PENDING' and available_at<=now()) or (status='SENDING' and lease_until<now()))
                    order by available_at for update skip locked limit 1
                ) update study_mail_outbox o set status='SENDING',attempts=attempts+1,
                    lease_token=?,lease_until=now()+interval '10 minutes'
                  from picked p where o.notification_id=p.notification_id returning o.notification_id,o.attempts
                """,token);
            if(rows.isEmpty()) break;
            long id=((Number)rows.getFirst().get("notification_id")).longValue();
            int attempts=((Number)rows.getFirst().get("attempts")).intValue();
            var messages=jdbc.queryForList("""
                select n.title,n.body,e.email,coalesce(s.email_enabled,true) enabled,u.status,
                exists(select 1 from school_member m where m.user_id=u.id and m.status='APPROVED') member
                from study_notification n join app_user u on u.id=n.user_id
                left join verified_login_email e on e.user_id=u.id
                left join study_goal_setting s on s.user_id=u.id where n.id=?
                """,id);
            if(messages.isEmpty()) continue;
            var m=messages.getFirst();
            if(!Boolean.TRUE.equals(m.get("enabled")) || !"ACTIVE".equals(m.get("status")) || !Boolean.TRUE.equals(m.get("member")) || m.get("email")==null) {
                finish(id,token,"SKIPPED","未绑定已验证邮箱、邮件提醒已关闭或账号不再符合接收条件");continue;
            }
            try {
                BrandedMail.sendNotice(mail,from,(String)m.get("email"),"AlgoHub · "+m.get("title"),
                        (String)m.get("title"),(String)m.get("body"),
                        "在「目标与提醒」可关闭邮件通知，站内消息仍会保留。");
                finish(id,token,"SENT",null);
            }
            catch(Exception e) {
                log.warn("学习邮件发送失败 notification={} attempt={}",id,attempts);
                jdbc.update("""
                    update study_mail_outbox set status=?,last_error='邮件服务发送失败',lease_token=null,lease_until=null,
                    available_at=now()+(? * interval '1 minute') where notification_id=? and lease_token=?
                    """,attempts>=5?"FAILED":"PENDING",Math.min(60,attempts*5),id,token);
            }
        }
    }
    private void finish(long id,UUID token,String status,String note) {
        jdbc.update("""
            update study_mail_outbox set status=?,last_error=?,sent_at=case when ?='SENT' then now() else null end,
            lease_token=null,lease_until=null where notification_id=? and lease_token=?
            """,status,note,status,id,token);
    }
}
