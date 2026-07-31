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

    @Scheduled(
            cron = "${manager.report-review.reminder-cron:0 */10 * * * *}",
            zone = "${manager.summary.zone:Asia/Irkutsk}"
    )
    @Transactional
    public void remind() {
        if (!appSettingService.getBoolean("manager.report-review.enabled", true)) return;
        int firstMinutes = boundedMinutes("manager.report-review.reminder-one-minutes", 60);
        int thirdMinutes = Math.max(
                firstMinutes + 10,
                boundedMinutes("manager.report-review.reminder-three-minutes", 180)
        );
        LocalDateTime now = LocalDateTime.now();
        List<ManagerReportReviewSession> pending =
                repository.findPendingForReminder(PENDING, now.minusMinutes(firstMinutes));
        for (ManagerReportReviewSession review : pending) {
            if (review.getDeadlineStartedAt() == null || review.getRecipientChatId() == null) continue;
            if (review.getAiUnavailableStartedAt() != null) {
                continue;
            }
            if (!qualityService.aiAvailable()) {
                aiAvailabilityService.pause(
                        review,
                        now,
                        "reminder-job",
                        "DeepSeek недоступен при проверке срока"
                );
                continue;
            }
            long ageMinutes = Duration.between(review.getDeadlineStartedAt(), now).toMinutes();
            if (ageMinutes >= thirdMinutes) {
                boolean changed = false;
                if (review.getRestrictedAt() == null) {
                    review.setRestrictedAt(now);
                    changed = true;
                    event(review, "RESTRICTION_APPLIED",
                            "Разбор не завершён за " + thirdMinutes + " мин.");
                    accessPolicy.invalidate(review.getManagerUserId());
                }
                if (review.getReminderThreeSentAt() == null && send(review, true)) {
                    review.setReminderThreeSentAt(now);
                    changed = true;
                }
                if (changed) repository.save(review);
            } else if (ageMinutes >= firstMinutes && review.getReminderOneSentAt() == null) {
                if (send(review, false)) {
                    review.setReminderOneSentAt(now);
                    repository.save(review);
                }
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
