package com.hunt.otziv.manager_control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

class ManagerControlWorkerReminderSchedulerTest {

    @Test
    void selectsUnresolvedTodaysCardsWhoseLastNotificationWasAtLeastThreeHoursAgo() {
        ManagerDailyControlConcreteItemRepository repository =
                Mockito.mock(ManagerDailyControlConcreteItemRepository.class);
        ManagerControlWorkerTaskTelegramCallbackService callbackService =
                Mockito.mock(ManagerControlWorkerTaskTelegramCallbackService.class);
        ManagerDailyControlConcreteItem waiting = new ManagerDailyControlConcreteItem();
        waiting.setId(41L);
        waiting.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        when(repository.findPendingWorkerExplanationReminders(
                any(),
                any(ManagerDailyControlItemStatus.class),
                any(LocalDate.class),
                any(Pageable.class)
        )).thenReturn(List.of(waiting));

        LocalDateTime before = LocalDateTime.now().minusHours(3);
        new ManagerControlWorkerReminderScheduler(repository, callbackService).remindPendingExplanations();
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
        verify(callbackService).remindPendingExplanation(eq(41L), any(LocalDateTime.class));
    }
}
