package com.szpu.algohub.account;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.szpu.algohub.platform.*;
import com.szpu.algohub.platform.PlatformDtos.*;
import com.szpu.algohub.problem.*;
import com.szpu.algohub.sync.*;

@SpringBootTest
class NowcoderWriteConcurrencyTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired NowcoderWriteTransactions writes;
    @Autowired NowcoderTrackerController tracker;
    @Autowired ProblemCatalogService catalog;
    @Autowired SyncService sync;
    @Autowired SyncTaskMapper tasks;
    @MockitoBean PlatformRegistry registry;
    long user, account;
    String prefix;
    PlatformAdapter adapter;

    @BeforeEach void setup() {
        prefix="write-lock-"+UUID.randomUUID();
        user=jdbc.queryForObject("insert into app_user(username,display_name) values (?,?) returning id",Long.class,prefix,prefix);
        account=jdbc.queryForObject("insert into platform_account(user_id,platform,platform_uid,handle,ownership_status) values (?,'NOWCODER',?,?,'VERIFIED') returning id",Long.class,user,prefix,prefix);
        adapter=mock(PlatformAdapter.class);
        when(registry.require(Platform.NOWCODER)).thenReturn(adapter);
        when(adapter.capability()).thenReturn(new PlatformCapability(Platform.NOWCODER,true,true,true,false,"OFFICIAL","OFFICIAL",false,"test"));
        for(int i=1;i<=2;i++) jdbc.update("insert into nowcoder_tracker_problem(account_id,pid,title,url,difficulty) values (?,?,?,?, '800')",account,prefix+i,"problem"+i,"https://example.invalid/"+i);
        when(adapter.fetchTotals(any())).thenReturn(new TotalsSnapshot(2,2,Map.of(),null));
        when(adapter.fetchProblemCatalog()).thenReturn(List.of(new CatalogProblem(problem(2),800,null),new CatalogProblem(problem(1),800,null)));
        when(adapter.fetchSubmissions(any())).thenAnswer(invocation -> {
            // No transaction (and therefore no writer lock) spans an upstream request.
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new SubmissionPage(List.of(sub(2),sub(1)),null,false,true);
        });
    }
    FetchedProblem problem(int i) { return new FetchedProblem(prefix+i,"problem"+i,"https://example.invalid/"+i,"800","800","OFFICIAL",List.of(),false); }
    FetchedSubmission sub(int i) { return new FetchedSubmission(prefix+i,problem(i),"AC",true,Instant.parse("2026-01-01T00:00:00Z"),"EXACT",null,null); }
    @AfterEach void cleanup() {
        jdbc.update("delete from app_user where id=?",user);
        jdbc.update("delete from problem where platform='NOWCODER' and problem_key like ?",prefix+"%");
    }

    @Test void accountCatalogAndTrackerWaitBeforeRowLocksThenAllCommit() throws Exception {
        sync.enqueueAccountSync(account,"NOWCODER",10000);
        SyncTask task=tasks.claimNext("concurrency-test",UUID.randomUUID().toString(),120,1);
        assertThat(task.getAccountId()).isEqualTo(account);
        try(var executor=Executors.newFixedThreadPool(3)) {
            List<Future<?>> futures=new ArrayList<>();
            writes.execute("NOWCODER",()-> {
                futures.add(executor.submit(()->sync.syncAccount(task)));
                futures.add(executor.submit(()->catalog.syncCatalog(Platform.NOWCODER)));
                futures.add(executor.submit(()->tracker.mergeCachedSolved()));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(System.nanoTime()<deadline) {
                    Integer waiting=jdbc.queryForObject("select count(*) from pg_locks where locktype='advisory' and classid=1095518024 and objid=1 and not granted",Integer.class);
                    if(waiting==3) break;
                    try { Thread.sleep(20); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                }
                assertThat(jdbc.queryForObject("select count(*) from pg_locks where locktype='advisory' and classid=1095518024 and objid=1 and not granted",Integer.class)).isEqualTo(3);
                // No waiter may hold an account/task lock while waiting for the common writer gate.
                jdbc.queryForList("select id from platform_account where id=? for update nowait",account);
                jdbc.queryForList("select id from sync_task where id=? for update nowait",task.getId());
                return null;
            });
            for(Future<?> future:futures) future.get(15,TimeUnit.SECONDS);
        }
        assertThat(tasks.findById(task.getId()).getStatus()).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("select sync_status from platform_account where id=?",String.class,account)).isEqualTo("OK");
        assertThat(jdbc.queryForObject("select count(*) from submission where account_id=?",Integer.class,account)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from user_solved_problem where account_id=?",Integer.class,account)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select completed from sync_cursor where account_id=? and cursor_kind='SUBMISSION_FORWARD'",Boolean.class,account)).isTrue();
    }

    @Test void rollbackReleasesGateAndDoesNotLeavePartialWrites() throws Exception {
        assertThatThrownBy(()->writes.execute("NOWCODER",()-> {
            jdbc.update("update platform_account set sync_status='RUNNING' where id=?",account);
            throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class);
        try(var executor=Executors.newSingleThreadExecutor()) {
            executor.submit(()->writes.execute("NOWCODER",()-> {
                assertThat(jdbc.queryForObject("select sync_status from platform_account where id=?",String.class,account)).isNotEqualTo("RUNNING");
                return null;
            })).get(5,TimeUnit.SECONDS);
        }
    }
}
