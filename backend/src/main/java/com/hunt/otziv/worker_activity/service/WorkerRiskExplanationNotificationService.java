package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.personal_reminders.api.SystemReminderCommands;
import com.hunt.otziv.t_telegrambot.api.TelegramNotifications;
import com.hunt.otziv.u_users.api.WorkerRiskReviewerDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Delivers reviewer notifications after the explanation transaction has committed. */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerRiskExplanationNotificationService {

    private static final String SOURCE_WORKER_EXPLANATION = "WORKER_RISK_WORKER_EXPLANATION";

    private final WorkerRiskReviewerDirectory reviewerDirectory;
    private final SystemReminderCommands reminderCommands;
    private final TelegramNotifications telegramNotifications;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyReviewers(Notification notification) {
        // Identity resolves recipient associations in this new persistence context;
        // only scalar snapshots cross its module API and the original commit boundary.
        for (var recipient : reviewerDirectory.reviewersForWorker(notification.workerUserId())) {
            try {
                reminderCommands.ensureOpenDueNow(new SystemReminderCommands.Reminder(
                            recipient.userId(),
                            "Получено пояснение специалиста",
                            limit(notification.text(), 1000),
                            SOURCE_WORKER_EXPLANATION,
                            notification.incidentId(),
                            notification.orderId()
                    ));
            } catch (RuntimeException exception) {
                log.warn("Не удалось создать напоминание о пояснении incidentId={}, userId={}",
                        notification.incidentId(), recipient.userId(), exception);
            }
            if (recipient.telegramChatId() != null) {
                try {
                    telegramNotifications.sendText(recipient.telegramChatId(), notification.text());
                } catch (RuntimeException exception) {
                    log.warn("Не удалось отправить пояснение специалиста incidentId={}, userId={}",
                            notification.incidentId(), recipient.userId(), exception);
                }
            }
        }
    }

    private String limit(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 1) + "…";
    }

    public record Notification(Long workerUserId, Long incidentId, Long orderId, String text) {}
}
