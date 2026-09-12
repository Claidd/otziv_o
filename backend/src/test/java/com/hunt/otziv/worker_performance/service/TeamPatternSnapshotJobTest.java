package com.hunt.otziv.worker_performance.service;

import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import com.hunt.otziv.u_users.api.TeamDirectoryReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class TeamPatternSnapshotJobTest {
    final TeamDirectoryReader directory = mock(TeamDirectoryReader.class);
    final TeamPatternAnalysisService analysis = mock(TeamPatternAnalysisService.class);
    final TeamPatternReadSnapshots snapshots = mock(TeamPatternReadSnapshots.class);
    final SchedulerLeaseService leases = mock(SchedulerLeaseService.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final TeamPatternSnapshotJob job = new TeamPatternSnapshotJob(directory, analysis, snapshots, leases, metrics, transactions);
    final SchedulerLeaseService.Lease lease = new SchedulerLeaseService.Lease("team-pattern-snapshot", "test", 1);

    void setup() {
        when(snapshots.enabled()).thenReturn(true);
        when(leases.tryAcquire(anyString(), any())).thenReturn(Optional.of(lease));
        when(directory.allActive(TeamDirectoryReader.Role.WORKER)).thenReturn(List.of(
                new TeamDirectoryReader.Member(1L, 101L, "one", "one", 1L, true)));
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Test void fencedSourcesAndCheckpointCommitBeforeLeaseRelease() {
        setup(); job.refresh();
        var order = inOrder(leases, analysis, transactions, snapshots);
        order.verify(leases).tryAcquire(anyString(), any());
        order.verify(transactions).getTransaction(any());
        order.verify(leases).holdForTransaction(eq(lease), any());
        order.verify(leases).projectionCursor(eq(lease), any());
        order.verify(analysis, times(2)).rebuildSnapshots(anyList(), any());
        order.verify(snapshots).cleanup(any());
        order.verify(leases).advanceProjectionCursor(eq(lease), any(), eq(0L));
        order.verify(transactions).commit(any());
        order.verify(leases).release(lease);
        assertThat(metrics.get("otziv.projection.refresh").tag("result", "success").counter().count()).isEqualTo(1);
    }

    @Test void failedRefreshRollsBackWithoutAdvancingCursorOrRecordingSuccess() {
        setup();
        doThrow(new IllegalStateException("source unavailable")).when(analysis).rebuildSnapshots(anyList(), any());
        job.refresh();
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
        verify(leases, never()).advanceProjectionCursor(any(), any(), anyLong());
        verify(leases).release(lease);
        assertThat(metrics.get("otziv.projection.refresh").tag("result", "error").counter().count()).isEqualTo(1);
    }

    @Test void unavailableLeaseDoesNotLoadOrPublishAnything() {
        when(snapshots.enabled()).thenReturn(true);
        when(leases.tryAcquire(anyString(), any())).thenReturn(Optional.empty());
        job.refresh();
        verifyNoInteractions(directory, analysis, transactions);
        verify(leases, never()).release(any());
    }
}
