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
import java.util.Optional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
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
        when(settings.getBoolean("manager.report-review.restriction-enabled", true)).thenReturn(true);
        when(qualityService.aiAvailable()).thenReturn(true);

        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setManagerUserId(17L);
        review.setRecipientChatId(700L);
        review.setDeliveredAt(LocalDateTime.now().minusHours(4));
        review.setDeadlineStartedAt(LocalDateTime.now().minusHours(4));
        review.setStatus(ManagerReportReviewStatus.PLAN_PENDING);
        when(repository.findPendingIdsForReminder(anyList(), any(LocalDateTime.class)))
                .thenReturn(List.of(review.getId()));
        when(repository.findForUpdateById(review.getId())).thenReturn(Optional.of(review));
        when(repository.restrictIfDue(any(), any(), any())).thenReturn(1);
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
                notificationMediaDeliveryService,
                settings,
                accessPolicy,
                qualityService,
                aiAvailabilityService,
                mock(ManagerReportReviewRolloutPolicy.class), transactions()
        );
        job.remind();

        assertThat(review.getRestrictedAt()).isNull(); // No managed session mutation/dirty flush.
        assertThat(review.getReminderThreeSentAt()).isNull();
        verify(repository).restrictIfDue(org.mockito.ArgumentMatchers.eq(41L), any(), any());
        verify(repository, never()).save(any());
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
        when(repository.findPendingIdsForReminder(anyList(), any(LocalDateTime.class)))
                .thenReturn(List.of(review.getId()));
        when(repository.findForUpdateById(review.getId())).thenReturn(Optional.of(review));

        ManagerReportReviewReminderJob job = new ManagerReportReviewReminderJob(
                repository,
                eventRepository,
                notificationMediaDeliveryService,
                settings,
                accessPolicy,
                qualityService,
                aiAvailabilityService,
                mock(ManagerReportReviewRolloutPolicy.class), transactions()
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

    @Test
    void disabledRestrictionSendsOverdueReminderWithoutLockingAccess() {
        Fixture fixture = new Fixture(true, false);
        ManagerReportReviewSession review = fixture.review(ManagerReportReviewStatus.QUESTION_PENDING, 4 * 60);
        when(fixture.media.send(any(String.class), any(Long.class), any(Long.class), any(String.class),
                any(String.class), anyList())).thenReturn(true);

        fixture.job().remind();

        verify(fixture.repository, never()).restrictIfDue(any(), any(), any());
        verify(fixture.media).send(any(String.class), any(Long.class), any(Long.class),
                org.mockito.ArgumentMatchers.argThat(text -> text.contains("Разбор не завершён за 3 часа")
                        && !text.contains("🔒") && !text.contains("доступен только личный кабинет")),
                any(String.class), anyList());
        verify(fixture.repository).markReminderSentIfDue(
                org.mockito.ArgumentMatchers.eq(41L), any(), any(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void overdueTextUsesTheConfiguredDeadline() {
        Fixture fixture = new Fixture(true, true);
        when(fixture.settings.getInt("manager.report-review.reminder-three-minutes", 180)).thenReturn(2880);
        fixture.review(ManagerReportReviewStatus.QUESTION_PENDING, 49 * 60);
        when(fixture.repository.restrictIfDue(any(), any(), any())).thenReturn(1);
        when(fixture.media.send(any(String.class), any(Long.class), any(Long.class), any(String.class),
                any(String.class), anyList())).thenReturn(true);

        fixture.job().remind();

        verify(fixture.media).send(any(String.class), any(Long.class), any(Long.class),
                org.mockito.ArgumentMatchers.argThat(text -> text.contains("за 48 часов")),
                any(String.class), anyList());
    }

    @Test
    void readingPauseIsReleasedWhenTheProviderIsBack() {
        Fixture fixture = new Fixture(true, true);
        ManagerReportReviewSession review = fixture.review(ManagerReportReviewStatus.READING, 70);
        review.setAiUnavailableStartedAt(LocalDateTime.now().minusMinutes(50));
        when(fixture.availability.resume(org.mockito.ArgumentMatchers.eq(review), any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq("reminder-job"))).thenAnswer(invocation -> {
                    review.setAiUnavailableStartedAt(null);
                    return true;
                });

        fixture.job().remind();

        verify(fixture.availability).resume(org.mockito.ArgumentMatchers.eq(review), any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.eq("reminder-job"));
        verify(fixture.media).send(any(String.class), any(Long.class), any(Long.class),
                org.mockito.ArgumentMatchers.argThat(text -> text.contains("Напоминание")),
                any(String.class), anyList());
    }

    @Test
    void answerPauseStaysForTheRecoveryJob() {
        Fixture fixture = new Fixture(true, true);
        ManagerReportReviewSession review = fixture.review(ManagerReportReviewStatus.QUESTION_PENDING, 300);
        review.setReadingConfirmedAt(LocalDateTime.now().minusHours(4));
        review.setAiUnavailableStartedAt(LocalDateTime.now().minusMinutes(50));

        fixture.job().remind();

        verify(fixture.availability, never()).resume(any(), any(), any());
        verify(fixture.repository, never()).restrictIfDue(any(), any(), any());
    }

    @Test
    void readingIsNotPausedWhileTheProviderIsDown() {
        Fixture fixture = new Fixture(false, true);
        fixture.review(ManagerReportReviewStatus.READING, 300);

        fixture.job().remind();

        verify(fixture.availability, never()).pause(any(), any(), any(), any());
        verify(fixture.repository, never()).restrictIfDue(any(), any(), any());
    }

    @Test
    void oneBrokenSessionDoesNotStopTheOthers() {
        Fixture fixture = new Fixture(true, true);
        ManagerReportReviewSession review = fixture.review(ManagerReportReviewStatus.QUESTION_PENDING, 300);
        when(fixture.repository.findPendingIdsForReminder(anyList(), any(LocalDateTime.class)))
                .thenReturn(List.of(40L, review.getId()));
        when(fixture.repository.findForUpdateById(40L)).thenThrow(new IllegalStateException("broken"));
        when(fixture.repository.restrictIfDue(any(), any(), any())).thenReturn(1);

        fixture.job().remind();

        verify(fixture.repository).restrictIfDue(org.mockito.ArgumentMatchers.eq(41L), any(), any());
    }

    private final class Fixture {
        final ManagerReportReviewSessionRepository repository = mock(ManagerReportReviewSessionRepository.class);
        final ManagerReportReviewEventRepository events = mock(ManagerReportReviewEventRepository.class);
        final NotificationMediaDeliveryService media = mock(NotificationMediaDeliveryService.class);
        final AppSettingService settings = mock(AppSettingService.class);
        final ManagerReportReviewQualityService quality = mock(ManagerReportReviewQualityService.class);
        final ManagerReportReviewAiAvailabilityService availability =
                mock(ManagerReportReviewAiAvailabilityService.class);

        Fixture(boolean aiAvailable, boolean restrictionEnabled) {
            when(settings.getBoolean("manager.report-review.enabled", true)).thenReturn(true);
            when(settings.getBoolean("manager.report-review.restriction-enabled", true)).thenReturn(restrictionEnabled);
            when(settings.getInt("manager.report-review.reminder-one-minutes", 60)).thenReturn(60);
            when(settings.getInt("manager.report-review.reminder-three-minutes", 180)).thenReturn(180);
            when(quality.aiAvailable()).thenReturn(aiAvailable);
        }

        ManagerReportReviewSession review(ManagerReportReviewStatus status, int ageMinutes) {
            ManagerReportReviewSession review = new ManagerReportReviewSession();
            review.setId(41L);
            review.setManagerUserId(17L);
            review.setRecipientChatId(700L);
            review.setDeadlineStartedAt(LocalDateTime.now().minusMinutes(ageMinutes));
            review.setStatus(status);
            when(repository.findPendingIdsForReminder(anyList(), any(LocalDateTime.class)))
                    .thenReturn(List.of(review.getId()));
            when(repository.findForUpdateById(review.getId())).thenReturn(Optional.of(review));
            return review;
        }

        ManagerReportReviewReminderJob job() {
            return new ManagerReportReviewReminderJob(repository, events, media,
                    settings, mock(ManagerReportReviewAccessPolicy.class), quality, availability,
                    mock(ManagerReportReviewRolloutPolicy.class), transactions());
        }
    }

    private PlatformTransactionManager transactions() {
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return transactions;
    }
}
