package com.hunt.otziv.workload_shadow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.workload_shadow.metrics.WorkloadShadowMetrics;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowNotificationStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadShadowNotificationDispatcherTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 27, 12, 0);

    @Mock private WorkloadShadowNotificationStore store;
    @Mock private AppSettingService settings;
    @Mock private TelegramService telegramService;
    @Mock private WorkloadShadowMetrics metrics;

    private WorkloadShadowNotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC);
        dispatcher = new WorkloadShadowNotificationDispatcher(
                store,
                settings,
                telegramService,
                metrics,
                clock
        );
        lenient().when(settings.getInt(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void runtimeFlagStopsDeliveryBeforeQueueAccess() {
        when(settings.getBoolean(
                WorkloadShadowNotificationDispatcher.GROUP_NOTIFICATIONS_ENABLED,
                false
        )).thenReturn(false);

        WorkloadShadowNotificationDispatcher.DispatchSummary summary = dispatcher.dispatchDue();

        assertThat(summary.disabled()).isTrue();
        verifyNoInteractions(store, telegramService, metrics);
    }

    @Test
    void sendsOnlyToConfiguredAdminOwnerMonitoringGroupWithShadowLabel() {
        prepareClaim(event(-100L, 0));
        when(telegramService.sendMessage(-100L, shadowText(), "HTML")).thenReturn(true);

        WorkloadShadowNotificationDispatcher.DispatchSummary summary = dispatcher.dispatchDue();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessage(
                org.mockito.ArgumentMatchers.eq(-100L),
                text.capture(),
                org.mockito.ArgumentMatchers.eq("HTML")
        );
        assertThat(text.getValue())
                .contains("SHADOW · РЕЖИМ НАБЛЮДЕНИЯ")
                .contains("ничего не передаёт")
                .contains("&lt;Компания&gt;")
                .doesNotContain("<Компания>");
        verify(store).applyDeliveryOutcomes(List.of(
                WorkloadShadowDeliveryOutcome.sent(event(-100L, 0), NOW)
        ), NOW, NOW.plusMinutes(5));
        verify(metrics).recordSent();
        assertThat(summary.sent()).isEqualTo(1);
        assertThat(summary.dead()).isZero();
    }

    @Test
    void positiveConfiguredChatIdStopsDeliveryBeforeQueueAccess() {
        when(settings.getBoolean(
                WorkloadShadowNotificationDispatcher.GROUP_NOTIFICATIONS_ENABLED,
                false
        )).thenReturn(true);
        when(settings.getStringAllowEmpty(
                WorkloadShadowNotificationDispatcher.NOTIFICATION_GROUP_CHAT_ID,
                ""
        )).thenReturn("123");

        WorkloadShadowNotificationDispatcher.DispatchSummary summary = dispatcher.dispatchDue();

        assertThat(summary.disabled()).isTrue();
        verifyNoInteractions(store, telegramService, metrics);
    }

    @Test
    void legacyManagerAuditTargetIsBlocked() {
        WorkloadShadowNotificationEvent legacyEvent = new WorkloadShadowNotificationEvent(
                1L,
                "WARNING",
                "STAFFING_REQUIRED",
                7L,
                "<Компания>",
                "Нужен сотрудник",
                "MANAGER_AUDIT",
                -200L,
                0
        );
        prepareClaim(legacyEvent);

        dispatcher.dispatchDue();

        verifyNoInteractions(telegramService);
        verify(store).applyDeliveryOutcomes(List.of(
                WorkloadShadowDeliveryOutcome.dead(
                        legacyEvent,
                        0,
                        WorkloadShadowNotificationDispatcher.ERROR_MISSING_GROUP_BINDING,
                        "MISSING_GROUP_BINDING: shadow-событие не направлено в общую группу администраторов и владельцев"
                )
        ), NOW, NOW.plusMinutes(5));
    }

    @Test
    void workerGroupTargetIsNeverUsedForShadowOffers() {
        WorkloadShadowNotificationEvent workerGroupEvent =
                new WorkloadShadowNotificationEvent(
                1L,
                "INFO",
                "SIMULATED_WORKER_OFFER",
                7L,
                "Предложение",
                "Взять компанию",
                "WORKER_GROUP",
                -300L,
                0
        );
        prepareClaim(workerGroupEvent);

        dispatcher.dispatchDue();

        verifyNoInteractions(telegramService);
        verify(store).applyDeliveryOutcomes(List.of(
                WorkloadShadowDeliveryOutcome.dead(
                        workerGroupEvent,
                        0,
                        WorkloadShadowNotificationDispatcher.ERROR_MISSING_GROUP_BINDING,
                        "MISSING_GROUP_BINDING: shadow-событие не направлено в общую группу администраторов и владельцев"
                )
        ), NOW, NOW.plusMinutes(5));
    }

    @Test
    void eventBoundToPreviousAdminGroupIsBlockedAfterChatChange() {
        WorkloadShadowNotificationEvent previousGroupEvent = event(-200L, 0);
        prepareClaim(previousGroupEvent);

        dispatcher.dispatchDue();

        verifyNoInteractions(telegramService);
        verify(store).applyDeliveryOutcomes(List.of(
                WorkloadShadowDeliveryOutcome.dead(
                        previousGroupEvent,
                        0,
                        WorkloadShadowNotificationDispatcher.ERROR_MISSING_GROUP_BINDING,
                        "MISSING_GROUP_BINDING: событие относится к другой Telegram-группе и не отправлено"
                )
        ), NOW, NOW.plusMinutes(5));
    }

    @Test
    void failedTelegramSendIsRetriedWithBackoff() {
        prepareClaim(event(-100L, 0));
        when(telegramService.sendMessage(-100L, shadowText(), "HTML")).thenReturn(false);

        WorkloadShadowNotificationDispatcher.DispatchSummary summary = dispatcher.dispatchDue();

        verify(store).applyDeliveryOutcomes(List.of(
                WorkloadShadowDeliveryOutcome.retry(
                        event(-100L, 0),
                        NOW.plusMinutes(1),
                        WorkloadShadowNotificationDispatcher.ERROR_TELEGRAM_SEND_FAILED,
                        "TELEGRAM_SEND_FAILED: TelegramService вернул false"
                )
        ), NOW, NOW.plusMinutes(5));
        verify(metrics).recordRetry();
        assertThat(summary.retried()).isEqualTo(1);
    }

    @Test
    void finalFailedAttemptBecomesDeadAndIsKeptForDiagnostics() {
        prepareClaim(event(-100L, 7));
        when(telegramService.sendMessage(-100L, shadowText(), "HTML")).thenReturn(false);

        WorkloadShadowNotificationDispatcher.DispatchSummary summary = dispatcher.dispatchDue();

        verify(store).applyDeliveryOutcomes(List.of(
                WorkloadShadowDeliveryOutcome.dead(
                        event(-100L, 7),
                        8,
                        WorkloadShadowNotificationDispatcher.ERROR_TELEGRAM_SEND_FAILED,
                        "TELEGRAM_SEND_FAILED: TelegramService вернул false"
                )
        ), NOW, NOW.plusMinutes(5));
        assertThat(summary.dead()).isEqualTo(1);
    }

    @Test
    void aggregatesOneHundredEventsIntoOneBoundedDigestAndOneBulkOutcome() {
        List<WorkloadShadowNotificationEvent> events = IntStream.rangeClosed(1, 100)
                .mapToObj(index -> notificationEvent(
                        index,
                        index == 100 ? "CRITICAL" : "INFO",
                        index == 100 ? "CRITICAL_EVENT" : "INFO_EVENT",
                        (index == 100 ? "Критическое" : "Информационное")
                                + " событие " + index,
                        "Подробное описание события " + index + " " + "x".repeat(500),
                        0
                ))
                .toList();
        when(settings.getInt("workload.shadow.notification-batch-size", 250))
                .thenReturn(1_000);
        prepareClaim(events);
        when(telegramService.sendMessage(
                org.mockito.ArgumentMatchers.eq(-100L),
                anyString(),
                org.mockito.ArgumentMatchers.eq("HTML")
        )).thenReturn(true);

        WorkloadShadowNotificationDispatcher.DispatchSummary summary = dispatcher.dispatchDue();

        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        verify(telegramService, times(1)).sendMessage(
                org.mockito.ArgumentMatchers.eq(-100L),
                digest.capture(),
                org.mockito.ArgumentMatchers.eq("HTML")
        );
        assertThat(digest.getValue())
                .contains("SHADOW · СВОДКА НАБЛЮДЕНИЯ")
                .contains("<b>Новых событий:</b> 100")
                .contains("… ещё 95 событий.")
                .hasSizeLessThan(3_900);
        assertThat(digest.getValue().indexOf("Критическое событие 100"))
                .isLessThan(digest.getValue().indexOf("Информационное событие 1"));

        List<Long> ids = events.stream()
                .map(WorkloadShadowNotificationEvent::id)
                .toList();
        verify(store).findDueEventIds(NOW, 250);
        verify(store).claim(ids, NOW, NOW.plusMinutes(5));
        verify(store).findClaimed(ids, NOW, NOW.plusMinutes(5));
        verify(store).applyDeliveryOutcomes(
                events.stream()
                        .map(value -> WorkloadShadowDeliveryOutcome.sent(value, NOW))
                        .toList(),
                NOW,
                NOW.plusMinutes(5)
        );
        verifyNoMoreInteractions(store);
        verify(metrics, times(100)).recordSent();
        assertThat(summary.scanned()).isEqualTo(100);
        assertThat(summary.claimed()).isEqualTo(100);
        assertThat(summary.sent()).isEqualTo(100);
        assertThat(summary.retried()).isZero();
        assertThat(summary.dead()).isZero();
    }

    @Test
    void failedDigestRetriesOrKillsEveryEventInTheClaimedBatch() {
        WorkloadShadowNotificationEvent first =
                notificationEvent(1, "WARNING", "FIRST", "Первое", "Сообщение", 0);
        WorkloadShadowNotificationEvent second =
                notificationEvent(2, "WARNING", "SECOND", "Второе", "Сообщение", 0);
        WorkloadShadowNotificationEvent third =
                notificationEvent(3, "CRITICAL", "THIRD", "Третье", "Сообщение", 1);
        WorkloadShadowNotificationEvent fourth =
                notificationEvent(4, "CRITICAL", "FOURTH", "Четвёртое", "Сообщение", 1);
        List<WorkloadShadowNotificationEvent> events =
                List.of(first, second, third, fourth);
        when(settings.getInt("workload.shadow.notification-max-attempts", 8))
                .thenReturn(2);
        prepareClaim(events);
        when(telegramService.sendMessage(-100L, digestText(), "HTML")).thenReturn(false);

        WorkloadShadowNotificationDispatcher.DispatchSummary summary = dispatcher.dispatchDue();

        verify(telegramService, times(1)).sendMessage(-100L, digestText(), "HTML");
        verify(store).applyDeliveryOutcomes(List.of(
                WorkloadShadowDeliveryOutcome.retry(
                        first,
                        NOW.plusMinutes(1),
                        WorkloadShadowNotificationDispatcher.ERROR_TELEGRAM_SEND_FAILED,
                        "TELEGRAM_SEND_FAILED: TelegramService вернул false"
                ),
                WorkloadShadowDeliveryOutcome.retry(
                        second,
                        NOW.plusMinutes(1),
                        WorkloadShadowNotificationDispatcher.ERROR_TELEGRAM_SEND_FAILED,
                        "TELEGRAM_SEND_FAILED: TelegramService вернул false"
                ),
                WorkloadShadowDeliveryOutcome.dead(
                        third,
                        2,
                        WorkloadShadowNotificationDispatcher.ERROR_TELEGRAM_SEND_FAILED,
                        "TELEGRAM_SEND_FAILED: TelegramService вернул false"
                ),
                WorkloadShadowDeliveryOutcome.dead(
                        fourth,
                        2,
                        WorkloadShadowNotificationDispatcher.ERROR_TELEGRAM_SEND_FAILED,
                        "TELEGRAM_SEND_FAILED: TelegramService вернул false"
                )
        ), NOW, NOW.plusMinutes(5));
        verify(metrics, times(2)).recordRetry();
        verify(metrics, times(2)).recordDead();
        assertThat(summary.sent()).isZero();
        assertThat(summary.retried()).isEqualTo(2);
        assertThat(summary.dead()).isEqualTo(2);
    }

    private void prepareClaim(WorkloadShadowNotificationEvent event) {
        prepareClaim(List.of(event));
    }

    private void prepareClaim(List<WorkloadShadowNotificationEvent> events) {
        when(settings.getBoolean(
                WorkloadShadowNotificationDispatcher.GROUP_NOTIFICATIONS_ENABLED,
                false
        )).thenReturn(true);
        when(settings.getStringAllowEmpty(
                WorkloadShadowNotificationDispatcher.NOTIFICATION_GROUP_CHAT_ID,
                ""
        )).thenReturn("-100");
        List<Long> eventIds = events.stream()
                .map(WorkloadShadowNotificationEvent::id)
                .toList();
        when(store.findDueEventIds(NOW, 250)).thenReturn(eventIds);
        when(store.claim(eventIds, NOW, NOW.plusMinutes(5))).thenReturn(events.size());
        when(store.findClaimed(eventIds, NOW, NOW.plusMinutes(5))).thenReturn(
                events.stream()
                        .map(WorkloadShadowClaimedNotification::new)
                        .toList()
        );
    }

    private WorkloadShadowNotificationEvent event(Long chatId, int attempts) {
        return new WorkloadShadowNotificationEvent(
                1L,
                "WARNING",
                "STAFFING_REQUIRED",
                7L,
                "<Компания>",
                "Нужен сотрудник",
                WorkloadShadowNotificationDispatcher.TARGET_ADMIN_OWNER_MONITORING,
                chatId,
                attempts
        );
    }

    private WorkloadShadowNotificationEvent notificationEvent(
            long id,
            String severity,
            String eventType,
            String title,
            String message,
            int attempts
    ) {
        return new WorkloadShadowNotificationEvent(
                id,
                severity,
                eventType,
                7L,
                title,
                message,
                WorkloadShadowNotificationDispatcher.TARGET_ADMIN_OWNER_MONITORING,
                -100L,
                attempts
        );
    }

    private String shadowText() {
        return "🟣 <b>SHADOW · РЕЖИМ НАБЛЮДЕНИЯ</b>\n"
                + "<i>Система ничего не передаёт и не меняет назначения.</i>\n\n"
                + "<b>Уровень:</b> WARNING\n"
                + "<b>&lt;Компания&gt;</b>\n"
                + "Нужен сотрудник\n\n"
                + "<code>STAFFING_REQUIRED</code>";
    }

    private String digestText() {
        return "🟣 <b>SHADOW · СВОДКА НАБЛЮДЕНИЯ</b>\n"
                + "<i>Система ничего не передаёт и не меняет назначения.</i>\n\n"
                + "<b>Новых событий:</b> 4\n"
                + "<b>Уровни:</b> WARNING — 2, CRITICAL — 2\n"
                + "<b>Типы:</b> FIRST — 1, SECOND — 1, THIRD — 1, FOURTH — 1\n\n"
                + "<b>Примеры:</b>\n"
                + "1. <code>THIRD</code> <b>Третье</b>\n"
                + "Сообщение\n"
                + "2. <code>FOURTH</code> <b>Четвёртое</b>\n"
                + "Сообщение\n"
                + "3. <code>FIRST</code> <b>Первое</b>\n"
                + "Сообщение\n"
                + "4. <code>SECOND</code> <b>Второе</b>\n"
                + "Сообщение\n\n"
                + "Полный список доступен на странице мониторинга SHADOW.";
    }
}
