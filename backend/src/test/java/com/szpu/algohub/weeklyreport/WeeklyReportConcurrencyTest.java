package com.szpu.algohub.weeklyreport;

import static org.assertj.core.api.Assertions.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real committed fixtures and separate PostgreSQL connections exercise the lock order. */
@SpringBootTest
class WeeklyReportConcurrencyTest {
    @Autowired JdbcTemplate db;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WeeklyReportService reports;
    @Autowired ReportCycleService cycles;
    @Autowired ReportReminderService reminders;
    @Autowired com.szpu.algohub.school.SchoolService schools;
    long school,user,cycle,report,extra;
    LocalDate week;
    TransactionTemplate tx() {return new TransactionTemplate(transactions);}
    @BeforeEach void fixture() {
        week=ReportTime.week(Instant.now());String name="wr-concurrency-"+UUID.randomUUID();
        tx().executeWithoutResult(s->{
            school=db.queryForObject("insert into school(name) values (?) returning id",Long.class,name);
            user=db.queryForObject("insert into app_user(username,display_name,role) values (?,?,'STUDENT') returning id",Long.class,name,name);
            db.update("insert into school_member(school_id,user_id,enrollment_year,status) values (?,?,2026,'APPROVED')",school,user);
            db.update("insert into weekly_report_setting(school_id,enabled,enabled_since) values (?,true,now())",school);
            cycle=db.queryForObject("insert into weekly_report_cycle(school_id,week_start,cutoff_at,email_enabled,reminder_done) values (?,?,now()+interval '1 hour',true,true) returning id",Long.class,school,week);
        });
    }
    @AfterEach void cleanup() {
        tx().executeWithoutResult(s->{
            db.update("delete from app_user where id in (?,?)",user,extra);
            db.update("delete from weekly_report_cycle where school_id=?",school);
            db.update("delete from weekly_report_setting where school_id=?",school);
            db.update("delete from school where id=?",school);
        });
    }
    void blocked(AtomicInteger pid) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end) {
            if(pid.get()!=0 && Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get())))return;
            Thread.sleep(15);
        }
        fail("Competing PostgreSQL transaction did not reach its expected lock");
    }
    @Test void snapshotWaitsForAnInFlightSubmissionBeforeReadingDeadlineState() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            CountDownLatch staged=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger pid=new AtomicInteger();
            var save=executor.submit(()->tx().executeWithoutResult(s->{
                var result=reports.save(user,new WeeklyReportService.Draft(null,week.toString(),0,"synthetic","",""),true);
                report=((Number)result.get("id")).longValue();staged.countDown();
                try {if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}
            }));
            assertThat(staged.await(5,TimeUnit.SECONDS)).isTrue();
            var snapshot=executor.submit(()->tx().executeWithoutResult(s->{pid.set(db.queryForObject("select pg_backend_pid()",Integer.class));cycles.snapshot(cycle,Instant.now().plusSeconds(3601));}));
            try {blocked(pid);} finally {release.countDown();}
            save.get(8,TimeUnit.SECONDS);snapshot.get(8,TimeUnit.SECONDS);
            assertThat(db.queryForObject("select submitted_count from weekly_report_cycle where id=?",Integer.class,cycle)).isEqualTo(1);
        }
    }
    @Test void snapshotWaitsForAnInFlightDraftDeletion() throws Exception {
        var draft=reports.save(user,new WeeklyReportService.Draft(null,week.toString(),0,"private","",""),false);report=((Number)draft.get("id")).longValue();
        try(var executor=Executors.newFixedThreadPool(2)) {
            CountDownLatch staged=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger pid=new AtomicInteger();
            var deleting=executor.submit(()->tx().executeWithoutResult(s->{
                reports.delete(user,report,((Number)draft.get("version")).intValue());staged.countDown();
                try {if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}
            }));
            assertThat(staged.await(5,TimeUnit.SECONDS)).isTrue();
            var snapshot=executor.submit(()->tx().executeWithoutResult(s->{pid.set(db.queryForObject("select pg_backend_pid()",Integer.class));cycles.snapshot(cycle,Instant.now().plusSeconds(3601));}));
            try {blocked(pid);} finally {release.countDown();}
            deleting.get(8,TimeUnit.SECONDS);snapshot.get(8,TimeUnit.SECONDS);
            assertThat(db.queryForObject("select cutoff_status from weekly_report_missing where cycle_id=? and user_id=?",String.class,cycle,user)).isEqualTo("NOT_CREATED");
        }
    }
    @Test void accountDeletionCanCascadeWhileDeliveryWaitsForUser() throws Exception {
        tx().executeWithoutResult(s->{
            long n=db.queryForObject("insert into study_notification(user_id,kind,dedup_key,title,body) values (?,'WEEKLY_REPORT_DUE','concurrent','Synthetic','Synthetic') returning id",Long.class,user);
            db.update("insert into weekly_report_reminder(cycle_id,user_id,enrollment_year,notification_id) values (?,?,2026,?)",cycle,user,n);
            db.update("insert into study_mail_outbox(notification_id) values (?)",n);
        });
        AtomicInteger pid=new AtomicInteger();
        try(var executor=Executors.newSingleThreadExecutor();Connection deleting=source.getConnection()) {
            deleting.setAutoCommit(false);deleting.createStatement().executeQuery("select id from app_user where id="+user+" for update").close();
            var delivery=executor.submit(()->tx().execute(s->{pid.set(db.queryForObject("select pg_backend_pid()",Integer.class));return reminders.deliverOne(school,Instant.now());}));
            blocked(pid);deleting.createStatement().execute("set local lock_timeout='1s'");
            deleting.createStatement().executeUpdate("delete from app_user where id="+user);deleting.commit();
            assertThat(delivery.get(8,TimeUnit.SECONDS)).isTrue();
            assertThat(db.queryForObject("select count(*) from weekly_report_reminder where cycle_id=?",Integer.class,cycle)).isZero();
        }
    }
    @Test void snapshotDrainsExistingMemberChangesBeforeReadingHistoricalRoster() throws Exception {
        long member=db.queryForObject("select id from school_member where school_id=? and user_id=?",Long.class,school,user);
        try(var executor=Executors.newFixedThreadPool(2)) {
            CountDownLatch staged=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger pid=new AtomicInteger();
            var editing=executor.submit(()->tx().executeWithoutResult(s->{
                schools.adminUpdateProfile(member,2026,"Updated class","42",user);staged.countDown();
                try {if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}
            }));
            assertThat(staged.await(5,TimeUnit.SECONDS)).isTrue();
            var snapshot=executor.submit(()->tx().executeWithoutResult(s->{pid.set(db.queryForObject("select pg_backend_pid()",Integer.class));cycles.snapshot(cycle,Instant.now().plusSeconds(3601));}));
            try {blocked(pid);} finally {release.countDown();}
            editing.get(8,TimeUnit.SECONDS);snapshot.get(8,TimeUnit.SECONDS);
            assertThat(db.queryForObject("select class_name from weekly_report_missing where cycle_id=? and user_id=?",String.class,cycle,user)).isEqualTo("Updated class");
        }
    }
    @Test void newlyCommittedMemberRestartsTheSnapshotWithoutTakingLocksInReverseOrder() throws Exception {
        String name="wr-new-"+UUID.randomUUID();
        extra=db.queryForObject("insert into app_user(username,display_name,role) values (?,?,'STUDENT') returning id",Long.class,name,name);
        AtomicInteger pid=new AtomicInteger();
        try(var executor=Executors.newSingleThreadExecutor();Connection joining=source.getConnection()) {
            joining.setAutoCommit(false);
            joining.createStatement().executeQuery("select id from app_user where id="+extra+" for update").close();
            joining.createStatement().executeUpdate("insert into school_member(school_id,user_id,enrollment_year,status) values ("+school+","+extra+",2026,'APPROVED')");
            var snapshot=executor.submit(()->tx().executeWithoutResult(s->{pid.set(db.queryForObject("select pg_backend_pid()",Integer.class));cycles.snapshot(cycle,Instant.now().plusSeconds(3601));}));
            blocked(pid);joining.commit();
            assertThatThrownBy(()->snapshot.get(8,TimeUnit.SECONDS)).hasCauseInstanceOf(com.szpu.algohub.common.ApiException.class);
            assertThat(db.queryForObject("select count(*) from weekly_report_missing where cycle_id=?",Integer.class,cycle)).isZero();
            cycles.snapshot(cycle,Instant.now().plusSeconds(3601));
            assertThat(db.queryForObject("select eligible_count from weekly_report_cycle where id=?",Integer.class,cycle)).isEqualTo(2);
        }
    }
}
