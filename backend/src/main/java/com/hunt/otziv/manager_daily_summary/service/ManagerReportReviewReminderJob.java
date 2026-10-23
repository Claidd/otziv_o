package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.notification_media.service.NotificationMediaEventCatalog;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewEvent;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewStatus;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerReportReviewReminderJob {

    private static final List<ManagerReportReviewStatus> PENDING = List.of(
            ManagerReportReviewStatus.DELIVERED,
            ManagerReportReviewStatus.READING,
            ManagerReportReviewStatus.QUESTION_PENDING,
            ManagerReportReviewStatus.PLAN_PENDING,
            ManagerReportReviewStatus.DISPUTE_PENDING
    );

    private final ManagerReportReviewSessionRepository repository;
    private final ManagerReportReviewEventRepository eventRepository;
    private final TelegramService telegramService;
    private final NotificationMediaDeliveryService notificationMediaDeliveryService;
    private final AppSettingService appSettingService;
    private final ManagerReportReviewAccessPolicy accessPolicy;
    private final ManagerReportReviewQualityService qualityService;
    private final ManagerReportReviewAiAvailabilityService aiAvailabilityService;
    private final ManagerReportReviewRolloutPolicy rolloutPolicy;
    private final PlatformTransactionManager transactionManager;

    @Scheduled(
            cron = "${manager.report-review.reminder-cron:0 */10 * * * *}",
            zone = "${manager.summary.zone:Asia/Irkutsk}"
    )
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void remind() {
        if (!appSettingService.getBoolean("manager.report-review.enabled", true)) return;
        int firstMinutes = boundedMinutes("manager.report-review.reminder-one-minutes", 60);
        int thirdMinutes = Math.max(
                firstMinutes + 10,
                boundedMinutes("manager.report-review.reminder-three-minutes", 180)
        );
        LocalDateTime now = LocalDateTime.now();
        // Do not carry a managed session/answers snapshot across another audit transaction.
        List<Long> pending = repository.findPendingIdsForReminder(PENDING, now.minusMinutes(firstMinutes));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        for (Long reviewId : pending) {
            transaction.executeWithoutResult(ignored -> remindOne(reviewId, firstMinutes, thirdMinutes));
        }
    }

    private void remindOne(Long reviewId, int firstMinutes, int thirdMinutes) {
            ManagerReportReviewSession review = repository.findForUpdateById(reviewId).orElse(null);
            if (review == null || review.isTestMode() || review.getCompletedAt() != null
                    || !PENDING.contains(review.getStatus())) return;
            LocalDateTime now = LocalDateTime.now();
            if (rolloutPolicy.isShadowFor(review)) return;
            if (review.getDeadlineStartedAt() == null || review.getRecipientChatId() == null) return;
            if (review.getAiUnavailableStartedAt() != null) {
                return;
            }
            if (!qualityService.aiAvailable()) {
                aiAvailabilityService.pause(
                        review,
                        now,
                        "reminder-job",
                        "DeepSeek недоступен при проверке срока"
                );
                return;
            }
            long ageMinutes = Duration.between(review.getDeadlineStartedAt(), now).toMinutes();
            if (ageMinutes >= thirdMinutes) {
                if (repository.restrictIfDue(reviewId, now, now.minusMinutes(thirdMinutes)) == 1) {
                    event(review, "RESTRICTION_APPLIED",
                            "Разбор не завершён за " + thirdMinutes + " мин.");
                    accessPolicy.invalidate(review.getManagerUserId());
                }
                if (review.getReminderThreeSentAt() == null && send(review, true)) {
                    repository.markReminderSentIfDue(reviewId, now, now.minusMinutes(thirdMinutes), true);
                }
            } else if (ageMinutes >= firstMinutes && review.getReminderOneSentAt() == null) {
                if (send(review, false)) {
                    repository.markReminderSentIfDue(reviewId, now, now.minusMinutes(firstMinutes), false);
                }
            }
    }

    private boolean send(ManagerReportReviewSession review, boolean repeated) {
        String progress = switch (review.getStatus()) {
            case QUESTION_PENDING -> "Вы остановились на вопросах по конкретным замечаниям.";
            case PLAN_PENDING -> "Осталось зафиксировать конкретный план на следующую смену.";
            case READING -> "Отчёт открыт, но прочтение ещё не подтверждено.";
            case DISPUTE_PENDING -> "Вы начали оспаривать отчёт, но не описали конкретную неточность.";
            default -> "Отчёт доставлен, но разбор ещё не начат.";
        };
        return notificationMediaDeliveryService.send(
                repeated
                        ? NotificationMediaEventCatalog.MANAGER_REPORT_OVERDUE.code()
                        : NotificationMediaEventCatalog.MANAGER_REPORT_REMINDER.code(),
                review.getRecipientChatId(),
                review.getManagerUserId(),
                (repeated ? "🔒 <b>Разбор не завершён за 3 часа</b>" : "🔔 <b>Напоминание</b>")
                        + "\n\n" + progress
                        + (repeated
                        ? "\n\nДо завершения разбора доступен только личный кабинет. "
                        + "После правильных ответов остальные разделы откроются автоматически."
                        : "\n\nВладельцу видны статус, время чтения и ответы."),
                "HTML",
                ManagerReportReviewTelegramService.continueKeyboard(review.getId())
        );
    }

    private int boundedMinutes(String key, int fallback) {
        return Math.max(10, Math.min(1440, appSettingService.getInt(key, fallback)));
    }

    private void event(ManagerReportReviewSession review, String type, String payload) {
        ManagerReportReviewEvent event = new ManagerReportReviewEvent();
        event.setReview(review);
        event.setEventType(type);
        event.setActorRole("SYSTEM");
        event.setSource("reminder-job");
        event.setPayloadText(payload);
        eventRepository.save(event);
    }
}
