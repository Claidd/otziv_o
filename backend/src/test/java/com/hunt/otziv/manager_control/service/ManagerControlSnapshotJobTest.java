package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import com.hunt.otziv.u_users.api.CabinetCacheScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

class ManagerControlSnapshotJobTest {
    @ParameterizedTest
    @ValueSource(ints = {10, 25})
    void lastRealBatchCompletesPassAndResetsCursorInSameTick(int managerCount) {
        var board = mock(ManagerControlBoardWorkflow.class);
        var snapshots = mock(ManagerControlReadSnapshots.class);
        var scope = mock(CabinetCacheScope.class);
        var leases = mock(SchedulerLeaseService.class);
        var transactions = mock(PlatformTransactionManager.class);
        var metrics = new SimpleMeterRegistry();
        var lease = new SchedulerLeaseService.Lease("manager-control-read-snapshots", "test", 1);
        AtomicLong cursor = new AtomicLong();
        when(leases.tryAcquire(anyString(), any())).thenReturn(Optional.of(lease));
        when(leases.projectionCursor(eq(lease), any())).thenAnswer(call -> cursor.get());
        doAnswer(call -> { cursor.set(call.getArgument(2)); return null; })
                .when(leases).advanceProjectionCursor(eq(lease), any(), anyLong());
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(board.snapshotManagerIds()).thenReturn(LongStream.rangeClosed(1, managerCount).boxed().toList());
        var job = new ManagerControlSnapshotJob(board, snapshots, scope, leases, transactions, metrics);
        job.refresh();
        verify(board, times(managerCount)).snapshotManager(anyLong(), any());
        verify(board).warmSnapshotScore(any());
        verify(snapshots).cleanup();
        verify(leases).release(lease);
        assertThat(cursor.get()).isZero();
        assertThat(metrics.get("otziv.projection.refresh").tag("result", "success").counter().count()).isEqualTo(1);
    }
}
