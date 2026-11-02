package com.szpu.algohub.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import com.szpu.algohub.account.*;
import com.szpu.algohub.common.*;
import com.szpu.algohub.platform.*;
import com.szpu.algohub.platform.PlatformDtos.*;
import com.szpu.algohub.statistics.*;

class LuoguPublicSyncTest {
    private final PlatformRegistry registry = mock(PlatformRegistry.class);
    private final PlatformAccountMapper accounts = mock(PlatformAccountMapper.class);
    private final PlatformCredentialMapper credentials = mock(PlatformCredentialMapper.class);
    private final PlatformAdapter adapter = mock(PlatformAdapter.class);
    private final TotalSnapshotMapper snapshots = mock(TotalSnapshotMapper.class);
    private final SyncCursorMapper cursors = mock(SyncCursorMapper.class);
    private final SubmissionMapper submissions = mock(SubmissionMapper.class);
    private final SyncTaskMapper tasks = mock(SyncTaskMapper.class);
    private final AccountDataStatusMapper dataStatus = mock(AccountDataStatusMapper.class);
    private final AvatarService avatars = mock(AvatarService.class);
    private final SyncService service = new SyncService(registry, accounts, credentials, null,
            null, submissions, null, mock(DailyStatMapper.class), snapshots, tasks, cursors,
            mock(PlatformThrottleMapper.class), dataStatus,
            mock(com.szpu.algohub.statistics.ContestParticipationMapper.class), null, null, null, avatars,
            mock(PlatformTransactionManager.class),
            new com.szpu.algohub.problem.NowcoderWriteTransactions(null, mock(PlatformTransactionManager.class)));
    private final SyncTask task = new SyncTask();

    LuoguPublicSyncTest() {
        var account = new PlatformAccount();
        account.setId(7L); account.setUserId(3L); account.setPlatform("LUOGU");
        account.setHandle("sample"); account.setPlatformUid("123");
        when(accounts.findById(7L)).thenReturn(account);
        when(accounts.lockForSync(7L)).thenReturn(account);
        task.setId(70L); task.setAccountId(7L); task.setPlatform("LUOGU"); task.setRunToken("run");
        when(tasks.lockOwned(70L, "run")).thenReturn(70L);
        when(registry.require(Platform.LUOGU)).thenReturn(adapter);
        when(adapter.capability()).thenReturn(new PlatformCapability(Platform.LUOGU,
                true, true, true, false, "OFFICIAL", "OFFICIAL", false, ""));
        when(adapter.fetchTotals(any())).thenReturn(new TotalsSnapshot(42, null, Map.of(), "public"));
        when(adapter.fetchSubmissions(any())).thenThrow(new ApiException(ErrorKind.CREDENTIAL_INVALID, "history requires credential"));
    }

    @Test
    void missingCredentialWithSavedCursorStillUpdatesPublicTotals() {
        when(cursors.findValue(7L, SyncService.CURSOR_FORWARD)).thenReturn("8");
        assertThat(service.syncAccount(task)).isFalse();
        verify(adapter).fetchTotals(argThat(context -> context.credential() == null));
        verify(adapter, never()).fetchSubmissions(any());
        verify(snapshots).insert(7L, 42, null, "SYNC", "public");
        verify(cursors, never()).save(any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(submissions);
        verify(tasks).markSuccess(eq(70L), eq("run"), anyLong());
    }

    @Test
    void publicSnapshotDoesNotResetCompletedHistory() {
        when(cursors.findCompleted(7L, SyncService.CURSOR_FORWARD)).thenReturn(true);
        service.syncAccount(task);
        verify(accounts).markSnapshotSuccess(7L, true);
        verify(accounts, never()).markSuccess(any(), anyBoolean());
    }

    @Test
    void publicFailureDoesNotWriteZeroOrClaimSuccess() {
        when(adapter.fetchTotals(any())).thenThrow(new ApiException(ErrorKind.RATE_LIMITED, "rate limited"));
        assertThatThrownBy(() -> service.syncAccount(task)).isInstanceOf(ApiException.class);
        verifyNoInteractions(snapshots);
        verify(accounts, never()).markSuccess(any(), anyBoolean());
    }

    @Test
    void finishedSyncRefreshesTheAvatarAfterwardsButAFailedOneDoesNot() {
        task.setTaskType("ACCOUNT_SYNC");
        service.execute(task);
        verify(avatars).refreshIfDue(7L);

        var failing = mock(AvatarService.class);
        var failingService = new SyncService(registry, accounts, credentials, null,
                null, submissions, null, mock(DailyStatMapper.class), snapshots, tasks, cursors,
                mock(PlatformThrottleMapper.class), dataStatus,
                mock(com.szpu.algohub.statistics.ContestParticipationMapper.class), null, null, null, failing,
                mock(PlatformTransactionManager.class),
            new com.szpu.algohub.problem.NowcoderWriteTransactions(null, mock(PlatformTransactionManager.class)));
        when(adapter.fetchTotals(any())).thenThrow(new ApiException(ErrorKind.RATE_LIMITED, "rate limited"));
        failingService.execute(task);
        verifyNoInteractions(failing);
    }

    @Test
    void lostLeaseDiscardsPublicTotals() {
        when(tasks.lockOwned(70L, "run")).thenReturn(null);
        assertThatThrownBy(() -> service.syncAccount(task)).isInstanceOf(SyncAbandonedException.class);
        verifyNoInteractions(snapshots);
        verify(accounts, never()).markSuccess(any(), anyBoolean());
    }
}
