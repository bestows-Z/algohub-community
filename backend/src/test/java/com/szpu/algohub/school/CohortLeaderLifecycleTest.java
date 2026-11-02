package com.szpu.algohub.school;

import static org.assertj.core.api.Assertions.*;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.szpu.algohub.common.ApiException;

/** Committed synthetic fixtures and independent PostgreSQL transactions exercise actual locks. */
@SpringBootTest
class CohortLeaderLifecycleTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CohortLeaderService service;
    @Autowired CohortLeaderAccess access;
    long school, actor, student;
    TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    long user(boolean admin) {
        String name="cohort-lock-"+UUID.randomUUID();
        return db.queryForObject("insert into app_user(username,display_name,role) values (?,?,?) returning id",Long.class,name,name,admin?"ADMIN":"STUDENT");
    }
    void fixture(boolean admin,boolean actorFirst) {
        tx().executeWithoutResult(status->{
            school=db.queryForObject("insert into school(name) values (?) returning id",Long.class,"Cohort locks "+UUID.randomUUID());
            if(actorFirst) { actor=user(admin); student=user(false); } else { student=user(false); actor=user(admin); }
            db.update("insert into school_member(school_id,user_id,status,enrollment_year) values (?,?,'APPROVED',2026)",school,student);
            if(!admin) db.update("insert into school_administrator(user_id,school_id) values (?,?)",actor,school);
        });
    }
    @AfterEach void cleanup() {
        if(school==0) return;
        tx().executeWithoutResult(status->{
            db.update("delete from school_cohort_leader where school_id=?",school);
            db.update("delete from school_administrator where school_id=?",school);
            db.update("delete from school_member where school_id=?",school);
            db.update("delete from auth_event where user_id in (?,?)",actor,student);
            db.update("delete from app_user where id in (?,?)",actor,student);
            db.update("delete from school where id=?",school);
        });
    }
    void awaitBlocked(AtomicInteger pid) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            if(pid.get()!=0 && Boolean.TRUE.equals(db.queryForObject(
                    "select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get()))) return;
            Thread.sleep(20);
        }
        fail("The competing transaction never reached the expected PostgreSQL lock");
    }
    @Test void platformDelegationLocksAdminBeforeLowerIdStudent() throws Exception {
        fixture(true,false);
        AtomicInteger pid=new AtomicInteger();
        try(var executor=Executors.newSingleThreadExecutor(); Connection oldAdmin=dataSource.getConnection()) {
            oldAdmin.setAutoCommit(false);
            oldAdmin.createStatement().executeQuery("select id from app_user where role='ADMIN' order by id for update").close();
            var granting=executor.submit(()->tx().execute(status->{
                pid.set(db.queryForObject("select pg_backend_pid()",Integer.class));
                return service.grant(actor,school,student,2026);
            }));
            awaitBlocked(pid);
            // The blocked grant must not hold the smaller student row ahead of the administrator.
            try(Connection probe=dataSource.getConnection()) {
                probe.setAutoCommit(false);
                probe.createStatement().executeQuery("select id from app_user where id="+student+" for no key update nowait").close();
                probe.rollback();
            }
            oldAdmin.createStatement().executeUpdate("update app_user set status='DISABLED' where id="+student);
            oldAdmin.commit();
            assertThatThrownBy(()->granting.get(8,TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
        }
    }
    @Test void existingMemberEditCanAuditWhileDelegationWaitsForStudent() throws Exception {
        fixture(false,true);
        AtomicInteger pid=new AtomicInteger();
        try(var executor=Executors.newSingleThreadExecutor(); Connection oldEdit=dataSource.getConnection()) {
            oldEdit.setAutoCommit(false);
            oldEdit.createStatement().executeQuery("select id from app_user where id="+student+" for update").close();
            var granting=executor.submit(()->tx().execute(status->{
                pid.set(db.queryForObject("select pg_backend_pid()",Integer.class));
                return service.grant(actor,school,student,2026);
            }));
            awaitBlocked(pid);
            oldEdit.createStatement().execute("set local lock_timeout='500ms'");
            // auth_event's FK takes KEY SHARE on actor; it must not conflict with the new helper.
            oldEdit.createStatement().executeUpdate("insert into auth_event(user_id,event) values ("+actor+",'ADMIN_MEMBER_PROFILE_CHANGED')");
            oldEdit.commit();
            assertThat(granting.get(8,TimeUnit.SECONDS).get("userId")).isEqualTo(student);
        }
    }
    @Test void revokeWaitsForInFlightScopeAndNewRequestsThenFail() throws Exception {
        fixture(false,true);
        long grant=((Number)service.grant(actor,school,student,2026).get("id")).longValue();
        CountDownLatch held=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicInteger pid=new AtomicInteger();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var operation=executor.submit(()->tx().executeWithoutResult(status->{
                access.requireScope(student,school,2026); held.countDown();
                try { if(!release.await(8,TimeUnit.SECONDS)) throw new IllegalStateException("scope release timed out"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            var revoking=executor.submit(()->tx().executeWithoutResult(status->{
                pid.set(db.queryForObject("select pg_backend_pid()",Integer.class));
                service.revoke(actor,school,grant);
            }));
            try {
                awaitBlocked(pid);
                assertThat(db.queryForObject("select revoked_at is null from school_cohort_leader where id=?",Boolean.class,grant)).isTrue();
            } finally { release.countDown(); }
            operation.get(8,TimeUnit.SECONDS); revoking.get(8,TimeUnit.SECONDS);
            assertThatThrownBy(()->tx().executeWithoutResult(status->access.requireScope(student,school,2026))).isInstanceOf(ApiException.class);
        } finally { release.countDown(); }
    }
}
