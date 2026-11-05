package com.hunt.otziv.manager_control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

class ManagerControlWorkerReminderSchedulerTest {

    private final ManagerDailyControlConcreteItemRepository repository =
            Mockito.mock(ManagerDailyControlConcreteItemRepository.class);
    private final ManagerControlWorkerTaskTelegramCallbackService callbackService =
            Mockito.mock(ManagerControlWorkerTaskTelegramCallbackService.class);
    private final SchedulerLeaseService leases = Mockito.mock(SchedulerLeaseService.class);
    private final SchedulerLeaseService.Lease lease = new SchedulerLeaseService.Lease("fixture", "owner", 1);
    private final ManagerControlWorkerReminderScheduler scheduler =
            new ManagerControlWorkerReminderScheduler(repository, callbackService, leases);

    @Test
    void selectsUnresolvedTodaysCardsWhoseLastNotificationWasAtLeastThreeHoursAgo() {
        when(leases.tryAcquire(anyString(), any(Duration.class))).thenReturn(Optional.of(lease));
        ManagerDailyControlConcreteItem waiting = item(41L);
        when(repository.findPendingWorkerExplanationReminders(
                any(),
                any(ManagerDailyControlItemStatus.class),
                any(LocalDate.class),
                any(Pageable.class)
        )).thenReturn(List.of(waiting));

        LocalDateTime before = LocalDateTime.now().minusHours(3);
        scheduler.remindPendingExplanations();
        LocalDateTime after = LocalDateTime.now().minusHours(3);

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDate> controlDate = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository).findPendingWorkerExplanationReminders(
                cutoff.capture(),
                eq(ManagerDailyControlItemStatus.RESOLVED),
                controlDate.capture(),
                any(Pageable.class)
        );
        assertTrue(!cutoff.getValue().isBefore(before) && !cutoff.getValue().isAfter(after),
                "Повторное напоминание должно выбираться с интервалом ровно три часа");
        assertTrue(Duration.between(cutoff.getValue(), LocalDateTime.now()).toMinutes() >= 179);
        assertEquals(cutoff.getValue().plusHours(3).toLocalDate(), controlDate.getValue());
        verify(callbackService).remindPendingExplanation(eq(41L), any(LocalDateTime.class), any(Runnable.class));
        verify(leases).tryAcquire(ManagerControlWorkerReminderScheduler.LEASE_NAME,
                ManagerControlWorkerReminderScheduler.LEASE_DURATION);
        verify(leases).release(lease);
    }

    @Test
    void anotherInstanceHoldingTheLeaseSkipsThePass() {
        when(leases.tryAcquire(anyString(), any(Duration.class))).thenReturn(Optional.empty());

        scheduler.remindPendingExplanations();

        verifyNoInteractions(repository, callbackService);
        verify(leases, never()).release(any());
    }

    @Test
    void eachClaimRenewsTheLeaseAndALostLeaseStopsThePass() {
        when(leases.tryAcquire(anyString(), any(Duration.class))).thenReturn(Optional.of(lease));
        when(repository.findPendingWorkerExplanationReminders(any(), any(), any(), any()))
                .thenReturn(List.of(item(41L), item(42L), item(43L)));
        // The callback runs the fence inside its claim transaction.
        doAnswer(call -> {
            call.<Runnable>getArgument(2).run();
            return true;
        }).when(callbackService).remindPendingExplanation(any(Long.class), any(LocalDateTime.class), any(Runnable.class));
        doAnswer(call -> null).doThrow(new IllegalStateException("Projection refresh lease lost"))
                .when(leases).holdForTransaction(lease, ManagerControlWorkerReminderScheduler.LEASE_DURATION);

        scheduler.remindPendingExplanations();

        verify(callbackService).remindPendingExplanation(eq(41L), any(LocalDateTime.class), any(Runnable.class));
        verify(callbackService).remindPendingExplanation(eq(42L), any(LocalDateTime.class), any(Runnable.class));
        verify(callbackService, never()).remindPendingExplanation(eq(43L), any(LocalDateTime.class), any(Runnable.class));
        verify(leases).release(lease);
    }

    @Test
    void oneFailingCardDoesNotStopTheOthers() {
        when(leases.tryAcquire(anyString(), any(Duration.class))).thenReturn(Optional.of(lease));
        when(repository.findPendingWorkerExplanationReminders(any(), any(), any(), any()))
                .thenReturn(List.of(item(41L), item(42L)));
        doThrow(new IllegalStateException("telegram down")).when(callbackService)
                .remindPendingExplanation(eq(41L), any(LocalDateTime.class), any(Runnable.class));

        scheduler.remindPendingExplanations();

        verify(callbackService).remindPendingExplanation(eq(42L), any(LocalDateTime.class), any(Runnable.class));
        verify(leases).release(lease);
    }

    private ManagerDailyControlConcreteItem item(long id) {
        ManagerDailyControlConcreteItem item = new ManagerDailyControlConcreteItem();
        item.setId(id);
        item.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        return item;
    }
}
