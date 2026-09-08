package com.hunt.otziv.worker_activity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.personal_reminders.api.SystemReminderCommands;
import com.hunt.otziv.t_telegrambot.api.TelegramNotifications;
import com.hunt.otziv.u_users.api.WorkerRiskReviewerDirectory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkerRiskExplanationNotificationServiceTest {
    private final WorkerRiskReviewerDirectory directory = mock(WorkerRiskReviewerDirectory.class);
    private final SystemReminderCommands reminders = mock(SystemReminderCommands.class);
    private final TelegramNotifications telegram = mock(TelegramNotifications.class);
    private final WorkerRiskExplanationNotificationService service =
            new WorkerRiskExplanationNotificationService(directory, reminders, telegram);

    @Test
    void reminderFailureDoesNotSuppressTelegramOrOtherRecipientsAndTelegramFailureDoesNotStopLoop() {
        when(directory.reviewersForWorker(1L)).thenReturn(List.of(
                new WorkerRiskReviewerDirectory.Reviewer(2L, 102L),
                new WorkerRiskReviewerDirectory.Reviewer(3L, 103L)));
        doThrow(new IllegalStateException("reminder unavailable")).when(reminders).ensureOpenDueNow(any());
        when(telegram.sendText(102L, "explanation")).thenThrow(new IllegalStateException("telegram unavailable"));

        service.notifyReviewers(new WorkerRiskExplanationNotificationService.Notification(1L, 10L, 20L, "explanation"));

        verify(telegram).sendText(102L, "explanation");
        verify(telegram).sendText(103L, "explanation");
        verify(reminders).ensureOpenDueNow(new SystemReminderCommands.Reminder(
                3L, "Получено пояснение специалиста", "explanation", "WORKER_RISK_WORKER_EXPLANATION", 10L, 20L));
    }

    @Test
    void reviewerWithoutTelegramStillGetsBoundedReminderWithSourceIdentity() {
        when(directory.reviewersForWorker(1L)).thenReturn(List.of(new WorkerRiskReviewerDirectory.Reviewer(2L, null)));

        service.notifyReviewers(new WorkerRiskExplanationNotificationService.Notification(1L, 10L, 20L, "x".repeat(1100)));

        var command = ArgumentCaptor.forClass(SystemReminderCommands.Reminder.class);
        verify(reminders).ensureOpenDueNow(command.capture());
        assertThat(command.getValue().recipientUserId()).isEqualTo(2L);
        assertThat(command.getValue().text()).hasSize(1000).endsWith("…");
        assertThat(command.getValue().sourceId()).isEqualTo(10L);
        assertThat(command.getValue().sourceOrderId()).isEqualTo(20L);
        verifyNoInteractions(telegram);
    }

    @Test
    void missingRecipientsHaveNoDeliverySideEffects() {
        when(directory.reviewersForWorker(1L)).thenReturn(List.of());
        service.notifyReviewers(new WorkerRiskExplanationNotificationService.Notification(1L, 10L, 20L, "text"));
        verifyNoInteractions(reminders, telegram);
    }
}
