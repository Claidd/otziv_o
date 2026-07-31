package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewStatus;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import com.hunt.otziv.notification_media.service.NotificationMediaDeliveryService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagerReportReviewReminderJobTest {

    @Test
    void appliesRestrictionAfterThreeHoursEvenWhenTelegramReminderFails() {
        ManagerReportReviewSessionRepository repository = mock(ManagerReportReviewSessionRepository.class);
        ManagerReportReviewEventRepository eventRepository = mock(ManagerReportReviewEventRepository.class);
        TelegramService telegramService = mock(TelegramService.class);
        NotificationMediaDeliveryService notificationMediaDeliveryService =
                mock(NotificationMediaDeliveryService.class);
        AppSettingService settings = mock(AppSettingService.class);
        ManagerReportReviewAccessPolicy accessPolicy = mock(ManagerReportReviewAccessPolicy.class);
        ManagerReportReviewQualityService qualityService = mock(ManagerReportReviewQualityService.class);
        ManagerReportReviewAiAvailabilityService aiAvailabilityService =
                mock(ManagerReportReviewAiAvailabilityService.class);
        when(settings.getBoolean("manager.report-review.enabled", true)).thenReturn(true);
        when(settings.getInt("manager.report-review.reminder-one-minutes", 60)).thenReturn(60);
        when(settings.getInt("manager.report-review.reminder-three-minutes", 180)).thenReturn(180);
        when(qualityService.aiAvailable()).thenReturn(true);

        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(700L);
        review.setDeliveredAt(LocalDateTime.now().minusHours(4));
        review.setDeadlineStartedAt(LocalDateTime.now().minusHours(4));
        review.setStatus(ManagerReportReviewStatus.PLAN_PENDING);
        when(repository.findPendingForReminder(anyList(), any(LocalDateTime.class)))
                .thenReturn(List.of(review));
        when(notificationMediaDeliveryService.send(
                any(String.class),
                any(Long.class),
                any(Long.class),
                any(String.class),
                any(String.class),
                anyList()
        )).thenReturn(false);

        ManagerReportReviewReminderJob job = new ManagerReportReviewReminderJob(
                repository,
                eventRepository,
                telegramService,
                notificationMediaDeliveryService,
                settings,
                accessPolicy,
                qualityService,
                aiAvailabilityService
        );
        job.remind();

        assertThat(review.getRestrictedAt()).isNotNull();
        assertThat(review.getReminderThreeSentAt()).isNull();
        verify(repository).save(review);
        verify(eventRepository).save(any());
        verify(accessPolicy).invalidate(17L);
    }

    @Test
    void pausesDeadlineInsteadOfRestrictingWhenDeepSeekIsUnavailable() {
        ManagerReportReviewSessionRepository repository = mock(ManagerReportReviewSessionRepository.class);
        ManagerReportReviewEventRepository eventRepository = mock(ManagerReportReviewEventRepository.class);
        TelegramService telegramService = mock(TelegramService.class);
        NotificationMediaDeliveryService notificationMediaDeliveryService =
                mock(NotificationMediaDeliveryService.class);
        AppSettingService settings = mock(AppSettingService.class);
        ManagerReportReviewAccessPolicy accessPolicy = mock(ManagerReportReviewAccessPolicy.class);
        ManagerReportReviewQualityService qualityService = mock(ManagerReportReviewQualityService.class);
        ManagerReportReviewAiAvailabilityService aiAvailabilityService =
                mock(ManagerReportReviewAiAvailabilityService.class);
        when(settings.getBoolean("manager.report-review.enabled", true)).thenReturn(true);
        when(settings.getInt("manager.report-review.reminder-one-minutes", 60)).thenReturn(60);
        when(settings.getInt("manager.report-review.reminder-three-minutes", 180)).thenReturn(180);
        when(qualityService.aiAvailable()).thenReturn(false);

        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(700L);
        review.setDeadlineStartedAt(LocalDateTime.now().minusHours(4));
        review.setStatus(ManagerReportReviewStatus.QUESTION_PENDING);
        when(repository.findPendingForReminder(anyList(), any(LocalDateTime.class)))
                .thenReturn(List.of(review));

        ManagerReportReviewReminderJob job = new ManagerReportReviewReminderJob(
                repository,
                eventRepository,
                telegramService,
                notificationMediaDeliveryService,
                settings,
                accessPolicy,
                qualityService,
                aiAvailabilityService
        );
        job.remind();

        verify(aiAvailabilityService).pause(
                org.mockito.ArgumentMatchers.eq(review),
                any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq("reminder-job"),
                any(String.class)
        );
        verify(repository, never()).save(review);
        assertThat(review.getRestrictedAt()).isNull();
    }
}
