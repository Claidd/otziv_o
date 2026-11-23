package com.hunt.otziv.manager_control.service;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.hunt.otziv.client_messages.api.PeriodicReminderRestart;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PeriodicReminderRecoveryJobTest {
    @Test void boundedScanAdvancesPastFailuresAndOrphansAndWrapsWithoutReplayingSends() {
        var scheduled = mock(PeriodicReminderRestart.class);
        var repair = mock(ManagerControlReminderRestart.class);
        var leases = mock(SchedulerLeaseService.class);
        var lease = new SchedulerLeaseService.Lease("fixture", "owner", 1);
        when(leases.tryAcquire(anyString(), any(Duration.class))).thenReturn(Optional.of(lease));
        when(scheduled.deferredReminderRecoveryCandidates(0, 20)).thenReturn(List.of(
                new PeriodicReminderRestart.RecoveryCandidate(10, null), new PeriodicReminderRestart.RecoveryCandidate(20, 2L)));
        when(repair.recoverDeferredAutomatically(10, null)).thenThrow(new IllegalStateException("orphan"));
        when(scheduled.deferredReminderRecoveryCandidates(20, 20)).thenReturn(List.of());
        var queue = mock(ManagerClientMessageQueue.class);
        when(queue.ordersHeldByCancelledManual(anyInt())).thenReturn(List.of());
        var job = new PeriodicReminderRecoveryJob(scheduled, repair, queue, leases);
        job.recover(); job.recover(); job.recover();
        verify(repair, times(2)).recoverDeferredAutomatically(20, 2L);
        verify(scheduled).deferredReminderRecoveryCandidates(20, 20);
        verify(scheduled, times(2)).deferredReminderRecoveryCandidates(0, 20);
        verify(leases, times(3)).release(lease);
        verify(scheduled, never()).restartDeferredReminder(any(), any(), anyString());
    }

    @Test void anotherServersLeasePreventsConcurrentScan() {
        var scheduled = mock(PeriodicReminderRestart.class);
        var repair = mock(ManagerControlReminderRestart.class);
        var leases = mock(SchedulerLeaseService.class);
        when(leases.tryAcquire(anyString(), any(Duration.class))).thenReturn(Optional.empty());
        var queue = mock(ManagerClientMessageQueue.class);
        new PeriodicReminderRecoveryJob(scheduled, repair, queue, leases).recover();
        verifyNoInteractions(scheduled, repair, queue);
    }

    @Test void cancelledManualCommandsAreReleasedBeforeTheDeferredScanAndAFailureDoesNotStopIt() {
        var scheduled = mock(PeriodicReminderRestart.class);
        var repair = mock(ManagerControlReminderRestart.class);
        var leases = mock(SchedulerLeaseService.class);
        var queue = mock(ManagerClientMessageQueue.class);
        var lease = new SchedulerLeaseService.Lease("fixture", "owner", 1);
        when(leases.tryAcquire(anyString(), any(Duration.class))).thenReturn(Optional.of(lease));
        when(queue.ordersHeldByCancelledManual(10)).thenReturn(List.of(27804L, 27805L));
        when(repair.releaseCancelledManualAutomatically(27804L)).thenThrow(new IllegalStateException("held"));
        when(repair.releaseCancelledManualAutomatically(27805L)).thenReturn(1);
        when(scheduled.deferredReminderRecoveryCandidates(0, 20)).thenReturn(List.of());
        new PeriodicReminderRecoveryJob(scheduled, repair, queue, leases).recover();
        verify(repair).releaseCancelledManualAutomatically(27804L);
        verify(repair).releaseCancelledManualAutomatically(27805L);
        verify(scheduled).deferredReminderRecoveryCandidates(0, 20);
        verify(leases).release(lease);
    }
}
