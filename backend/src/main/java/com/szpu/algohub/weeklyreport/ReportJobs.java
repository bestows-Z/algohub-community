package com.szpu.algohub.weeklyreport;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ReportJobs {
    private static final Logger log=LoggerFactory.getLogger(ReportJobs.class);
    private final JdbcTemplate db;
    private final ReportCycleService cycles;
    private final ReportReminderService reminders;
    private final ReportTime clock;
    private final boolean automaticMail;
    public ReportJobs(JdbcTemplate db,ReportCycleService cycles,ReportReminderService reminders,ReportTime clock,
        @Value("${algohub.reminders.enabled:false}") boolean automaticMail) {
        this.db=db;this.cycles=cycles;this.reminders=reminders;this.clock=clock;this.automaticMail=automaticMail;
    }
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void scheduled() { tick(clock.now(),automaticMail); }
    public void tick(Instant now,boolean mailEnabled) {
        db.update("update weekly_report_cycle set reminder_done=true where not reminder_done and cutoff_at<=?",ReportTime.sql(now));
        for(var setting:db.queryForList("select school_id,enabled_since from weekly_report_setting where enabled order by school_id")) {
            long school=((Number)setting.get("school_id")).longValue();
            var missingWeeks=db.queryForList("""
                select d::date from generate_series(cast(? as timestamp),cast(? as timestamp),interval '1 week') d
                where not exists(select 1 from weekly_report_cycle c where c.school_id=? and c.week_start=d::date)
                order by d limit 100
                """,java.sql.Date.class,ReportTime.week(ReportTime.instant(setting.get("enabled_since"))),ReportTime.week(now),school);
            for(var start:missingWeeks) try { cycles.ensureCycle(school,start.toLocalDate(),now); }
            catch(Exception e) {log.warn("周报周期准备失败 school={}",school);}
        }
        for(long id:db.queryForList("""
            select c.id from weekly_report_cycle c join weekly_report_setting s on s.school_id=c.school_id
            where s.enabled and ((c.status in ('PENDING','FAILED') and c.cutoff_at<=?)
                or (not c.reminder_done and c.remind_at<=?)) order by c.cutoff_at limit 100
            """,Long.class,ReportTime.sql(now),ReportTime.sql(now))) {
            if(mailEnabled) try {reminders.generate(id,now);} catch(Exception e) {log.warn("周报提醒生成失败 cycle={}",id);}
            try {cycles.snapshot(id,now);} catch(Exception e) {cycles.failed(id);log.warn("周报统计失败 cycle={}",id);}
        }
        if(mailEnabled) for(long school:db.queryForList("""
            select distinct c.school_id from weekly_report_reminder n join weekly_report_cycle c on c.id=n.cycle_id
            join study_mail_outbox o on o.notification_id=n.notification_id join weekly_report_setting s on s.school_id=c.school_id
            where s.enabled and ((o.status='PENDING' and o.available_at<=?) or (o.status='SENDING' and o.lease_until<?)) limit 100
            """,Long.class,ReportTime.sql(now),ReportTime.sql(now))) {
            for(int i=0;i<20;i++) try {if(!reminders.deliverOne(school,now))break;} catch(Exception e) {log.warn("周报邮件处理失败 school={}",school);break;}
        }
    }
}
