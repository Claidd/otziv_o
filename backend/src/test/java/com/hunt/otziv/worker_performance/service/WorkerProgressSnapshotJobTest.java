package com.hunt.otziv.worker_performance.service;

import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class WorkerProgressSnapshotJobTest {
    @Test void failedBatchIsRetriedWithoutAcknowledgingCheckpoint() {
        var progress=mock(StaffDailyProgressService.class);
        var leases=mock(SchedulerLeaseService.class);
        var transactions=mock(PlatformTransactionManager.class);
        var registry=new SimpleMeterRegistry();
        var lease=new SchedulerLeaseService.Lease("worker-progress-snapshot","owner",1);
        when(progress.progressEnabled()).thenReturn(true);
        when(leases.tryAcquire(anyString(),any(Duration.class))).thenReturn(Optional.of(lease));
        when(transactions.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        when(progress.refreshSnapshotBatch(anyLong(),any())).thenThrow(new IllegalStateException("synthetic failure")).thenReturn(0L);
        var job=new WorkerProgressSnapshotJob(progress,leases,transactions,registry);
        job.refresh();
        verify(leases,never()).advanceProjectionCursor(any(),any(),anyLong());
        verify(transactions).rollback(any());
        job.refresh();
        verify(leases).advanceProjectionCursor(eq(lease),any(),eq(0L));
        verify(leases,times(2)).release(lease);
        assertThat(registry.get("otziv.projection.refresh").tag("result","error").counter().count()).isOne();
        assertThat(registry.get("otziv.projection.refresh").tag("result","success").counter().count()).isOne();
    }
}
