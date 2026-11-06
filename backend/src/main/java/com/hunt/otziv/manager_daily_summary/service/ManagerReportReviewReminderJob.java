package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.notification_media.service.NotificationMediaEventCatalog;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewEvent;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewStatus;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
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
        int firstMinutes = ManagerReportReviewDeadline.firstReminderMinutes(appSettingService.getInt(
                ManagerReportReviewDeadline.FIRST_REMINDER_KEY, ManagerReportReviewDeadline.DEFAULT_FIRST_REMINDER_MINUTES));
        int deadlineMinutes = ManagerReportReviewDeadline.deadlineMinutes(appSettingService.getInt(
                ManagerReportReviewDeadline.DEADLINE_KEY, ManagerReportReviewDeadline.DEFAULT_DEADLINE_MINUTES));
        boolean restrictionEnabled = appSettingService.getBoolean(ManagerReportReviewDeadline.RESTRICTION_KEY, true);
        LocalDateTime now = LocalDateTime.now();
        // Do not carry a managed session/answers snapshot across another audit transaction.
        List<Long> pending = repository.findPendingIdsForReminder(
                PENDING, now.minusMinutes(Math.min(firstMinutes, deadlineMinutes)));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        for (Long reviewId : pending) {
            try {
                transaction.executeWithoutResult(ignored ->
                        remindOne(reviewId, firstMinutes, deadlineMinutes, restrictionEnabled));
            } catch (RuntimeException failure) {
                // One broken session must not stop reminders and restrictions for the others.
                log.warn("Manager audit reminder failed reviewId={} errorType={} message={}",
                        reviewId, failure.getClass().getSimpleName(), failure.getMessage(), failure);
            }
        }
    }

    private void remindOne(Long reviewId, int firstMinutes, int deadlineMinutes, boolean restrictionEnabled) {
            ManagerReportReviewSession review = repository.findForUpdateById(reviewId).orElse(null);
            if (review == null || review.isTestMode() || review.getCompletedAt() != null
                    || !PENDING.contains(review.getStatus())) return;
            LocalDateTime now = LocalDateTime.now();
            if (rolloutPolicy.isShadowFor(review)) return;
            if (review.getDeadlineStartedAt() == null || review.getRecipientChatId() == null) return;
            boolean aiAvailable = qualityService.aiAvailable();
            if (review.getAiUnavailableStartedAt() != null) {
                // Before reading is confirmed nothing waits for the provider; a pause taken then is
                // only released here. Later pauses belong to saved answers and are resumed by recovery.
                if (!aiAvailable || !readingPhase(review)
                        || !aiAvailabilityService.resume(review, now, "reminder-job")) return;
            }
            if (!aiAvailable) {
                // Only answer and plan checks need the provider; reading or writing a dispute does not.
                if (review.getStatus() == ManagerReportReviewStatus.QUESTION_PENDING
                        || review.getStatus() == ManagerReportReviewStatus.PLAN_PENDING) {
                    aiAvailabilityService.pause(
                            review,
                            now,
                            "reminder-job",
                            "DeepSeek недоступен при проверке срока"
                    );
                }
                // The access policy does not restrict while the provider is down, so neither do reminders.
                return;
            }
            long ageMinutes = Duration.between(review.getDeadlineStartedAt(), now).toMinutes();
            if (ageMinutes >= deadlineMinutes) {
                if (restrictionEnabled
                        && repository.restrictIfDue(reviewId, now, now.minusMinutes(deadlineMinutes)) == 1) {
                    event(review, "RESTRICTION_APPLIED",
                            "Разбор не завершён за " + deadlineMinutes + " мин.");
                    accessPolicy.invalidate(review.getManagerUserId());
                }
                if (review.getReminderThreeSentAt() == null && send(review, true, deadlineMinutes, restrictionEnabled)) {
                    repository.markReminderSentIfDue(reviewId, now, now.minusMinutes(deadlineMinutes), true);
                }
            } else if (ageMinutes >= firstMinutes && review.getReminderOneSentAt() == null) {
                if (send(review, false, deadlineMinutes, restrictionEnabled)) {
                    repository.markReminderSentIfDue(reviewId, now, now.minusMinutes(firstMinutes), false);
                }
            }
    }

    private boolean readingPhase(ManagerReportReviewSession review) {
        return review.getReadingConfirmedAt() == null
                && (review.getStatus() == ManagerReportReviewStatus.DELIVERED
                || review.getStatus() == ManagerReportReviewStatus.READING);
    }

    private boolean send(ManagerReportReviewSession review, boolean repeated, int deadlineMinutes,
                         boolean restrictionEnabled) {
        String progress = switch (review.getStatus()) {
            case QUESTION_PENDING -> "Вы остановились на вопросах по конкретным замечаниям.";
            case PLAN_PENDING -> "Осталось зафиксировать конкретный план на следующую смену.";
            case READING -> "Отчёт открыт, но прочтение ещё не подтверждено.";
            case DISPUTE_PENDING -> "Вы начали оспаривать отчёт, но не описали конкретную неточность.";
            default -> "Отчёт доставлен, но разбор ещё не начат.";
        };
        String duration = ManagerReportReviewDeadline.duration(deadlineMinutes);
        String title = !repeated
                ? "🔔 <b>Напоминание</b>"
                : restrictionEnabled
                        ? "🔒 <b>Разбор не завершён за " + duration + "</b>"
                        : "⏰ <b>Разбор не завершён за " + duration + "</b>";
        String tail = !repeated
                ? "\n\nВладельцу видны статус, время чтения и ответы."
                : restrictionEnabled
                        ? "\n\nДо завершения разбора доступен только личный кабинет. "
                        + "После правильных ответов остальные разделы откроются автоматически."
                        : "\n\nСрок прохождения истёк. Владельцу видны статус, время чтения и ответы.";
        return notificationMediaDeliveryService.send(
                repeated
                        ? NotificationMediaEventCatalog.MANAGER_REPORT_OVERDUE.code()
                        : NotificationMediaEventCatalog.MANAGER_REPORT_REMINDER.code(),
                review.getRecipientChatId(),
                review.getManagerUserId(),
                title + "\n\n" + progress + tail,
                "HTML",
                ManagerReportReviewTelegramService.continueKeyboard(review.getId())
        );
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
