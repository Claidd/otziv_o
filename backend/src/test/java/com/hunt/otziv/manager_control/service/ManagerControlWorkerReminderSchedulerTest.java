package com.hunt.otziv.manager_control.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

class ManagerControlWorkerReminderSchedulerTest {

    @Test
    void selectsOnlyItemsWhoseLastNotificationWasAtLeastThreeHoursAgo() {
        ManagerDailyControlConcreteItemRepository repository =
                Mockito.mock(ManagerDailyControlConcreteItemRepository.class);
        ManagerControlWorkerTaskTelegramCallbackService callbackService =
                Mockito.mock(ManagerControlWorkerTaskTelegramCallbackService.class);
        when(repository.findPendingWorkerExplanationReminders(any(), any(Pageable.class)))
                .thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now().minusHours(3);
        new ManagerControlWorkerReminderScheduler(repository, callbackService).remindPendingExplanations();
        LocalDateTime after = LocalDateTime.now().minusHours(3);

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).findPendingWorkerExplanationReminders(cutoff.capture(), any(Pageable.class));
        assertTrue(!cutoff.getValue().isBefore(before) && !cutoff.getValue().isAfter(after),
                "Повторное напоминание должно выбираться с интервалом ровно три часа");
        assertTrue(Duration.between(cutoff.getValue(), LocalDateTime.now()).toMinutes() >= 179);
    }
}
