package com.hunt.otziv.workload_shadow.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.repository.WorkloadShadowRecalculationLockRepository;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadShadowRecalculationLockServiceTest {

    private static final String INSTANCE_ID = "node-a";
    private static final String OWNER_TOKEN = "test-owner-token";

    @Mock private WorkloadShadowRecalculationLockRepository repository;
    @Mock private ScheduledExecutorService heartbeatExecutor;
    @Mock private ScheduledFuture<?> scheduledHeartbeat;

    private WorkloadShadowRecalculationLockService service;

    @BeforeEach
    void setUp() {
        service = new WorkloadShadowRecalculationLockService(
                repository,
                heartbeatExecutor,
                () -> OWNER_TOKEN
        );
    }

    @Test
    void acquiresAttachesRenewsAndReleasesWithTheSameOwnershipToken() {
        when(repository.tryAcquire(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN,
                WorkloadShadowRecalculationLockService.LEASE_SECONDS
        )).thenReturn(1);
        when(repository.attachRun(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN,
                42L,
                WorkloadShadowRecalculationLockService.LEASE_SECONDS
        )).thenReturn(1);
        doReturn(scheduledHeartbeat).when(heartbeatExecutor).scheduleWithFixedDelay(
                any(Runnable.class),
                eq((long) WorkloadShadowRecalculationLockService.HEARTBEAT_SECONDS),
                eq((long) WorkloadShadowRecalculationLockService.HEARTBEAT_SECONDS),
                eq(TimeUnit.SECONDS)
        );
        when(repository.renew(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN,
                WorkloadShadowRecalculationLockService.LEASE_SECONDS
        )).thenReturn(1);
        when(repository.release(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN
        )).thenReturn(1);

        WorkloadShadowRecalculationLease lease = service.tryAcquire(INSTANCE_ID).orElseThrow();
        lease.attachRun(42L);
        lease.checkpoint("AFTER_PROJECTION");
        lease.close();
        lease.close();

        InOrder ordered = inOrder(repository);
        ordered.verify(repository).ensureLockRow(WorkloadShadowRecalculationLockService.LOCK_NAME);
        ordered.verify(repository).tryAcquire(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN,
                WorkloadShadowRecalculationLockService.LEASE_SECONDS
        );
        ordered.verify(repository).attachRun(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN,
                42L,
                WorkloadShadowRecalculationLockService.LEASE_SECONDS
        );
        ordered.verify(repository).renew(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN,
                WorkloadShadowRecalculationLockService.LEASE_SECONDS
        );
        ordered.verify(repository).release(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN
        );
        verify(scheduledHeartbeat).cancel(false);
    }

    @Test
    void reportsBusyWhenAnotherUnexpiredOwnerHoldsTheRow() {
        when(repository.tryAcquire(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN,
                WorkloadShadowRecalculationLockService.LEASE_SECONDS
        )).thenReturn(0);

        Optional<WorkloadShadowRecalculationLease> lease = service.tryAcquire(INSTANCE_ID);

        assertThat(lease).isEmpty();
        verify(repository).ensureLockRow(WorkloadShadowRecalculationLockService.LOCK_NAME);
        verify(repository).tryAcquire(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN,
                WorkloadShadowRecalculationLockService.LEASE_SECONDS
        );
        verify(repository, never()).attachRun(
                any(),
                any(),
                any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verifyNoInteractions(heartbeatExecutor);
    }

    @Test
    void heartbeatOwnershipLossMakesTheNextCheckpointFailClosed() {
        when(repository.tryAcquire(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN,
                WorkloadShadowRecalculationLockService.LEASE_SECONDS
        )).thenReturn(1);
        when(repository.attachRun(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN,
                77L,
                WorkloadShadowRecalculationLockService.LEASE_SECONDS
        )).thenReturn(1);
        doReturn(scheduledHeartbeat).when(heartbeatExecutor).scheduleWithFixedDelay(
                any(Runnable.class),
                eq((long) WorkloadShadowRecalculationLockService.HEARTBEAT_SECONDS),
                eq((long) WorkloadShadowRecalculationLockService.HEARTBEAT_SECONDS),
                eq(TimeUnit.SECONDS)
        );
        when(repository.renew(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN,
                WorkloadShadowRecalculationLockService.LEASE_SECONDS
        )).thenReturn(0);

        WorkloadShadowRecalculationLease lease = service.tryAcquire(INSTANCE_ID).orElseThrow();
        lease.attachRun(77L);
        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        verify(heartbeatExecutor).scheduleWithFixedDelay(
                heartbeat.capture(),
                eq((long) WorkloadShadowRecalculationLockService.HEARTBEAT_SECONDS),
                eq((long) WorkloadShadowRecalculationLockService.HEARTBEAT_SECONDS),
                eq(TimeUnit.SECONDS)
        );

        heartbeat.getValue().run();

        assertThatThrownBy(() -> lease.checkpoint("AFTER_PROJECTION"))
                .isInstanceOf(WorkloadShadowLeaseLostException.class)
                .hasMessageContaining("heartbeat");
        verify(repository).renew(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN,
                WorkloadShadowRecalculationLockService.LEASE_SECONDS
        );
        verify(scheduledHeartbeat).cancel(false);
        lease.close();
    }

    @Test
    void failedRunAttachmentDoesNotStartHeartbeatAndStillAllowsTokenSafeRelease() {
        when(repository.tryAcquire(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN,
                WorkloadShadowRecalculationLockService.LEASE_SECONDS
        )).thenReturn(1);
        when(repository.attachRun(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN,
                91L,
                WorkloadShadowRecalculationLockService.LEASE_SECONDS
        )).thenReturn(0);

        WorkloadShadowRecalculationLease lease = service.tryAcquire(INSTANCE_ID).orElseThrow();

        assertThatThrownBy(() -> lease.attachRun(91L))
                .isInstanceOf(WorkloadShadowLeaseLostException.class)
                .hasMessageContaining("91");
        verifyNoInteractions(heartbeatExecutor);

        lease.close();
        verify(repository).release(
                WorkloadShadowRecalculationLockService.LOCK_NAME,
                INSTANCE_ID,
                OWNER_TOKEN
        );
    }
}
