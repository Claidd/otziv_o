package com.hunt.otziv.workload_shadow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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
                true
        )).thenReturn(false);

        WorkloadShadowNotificationDispatcher.DispatchSummary summary = dispatcher.dispatchDue();

        assertThat(summary.disabled()).isTrue();
        verifyNoInteractions(store, telegramService, metrics);
    }

    @Test
    void sendsOnlyToVerifiedNegativeManagerAuditGroupWithShadowLabel() {
        prepareClaim(event(-100L, 0), -100L);
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
    void positiveChatIdIsDeadMissingGroupAndNeverSent() {
        prepareClaim(event(123L, 0), null);

        WorkloadShadowNotificationDispatcher.DispatchSummary summary = dispatcher.dispatchDue();

        verify(store).applyDeliveryOutcomes(List.of(
                WorkloadShadowDeliveryOutcome.dead(
                        event(123L, 0),
                        0,
                        WorkloadShadowNotificationDispatcher.ERROR_MISSING_GROUP_BINDING,
                        "MISSING_GROUP_BINDING: target должен быть действующей audit-группой менеджера"
                )
        ), NOW, NOW.plusMinutes(5));
        verifyNoInteractions(telegramService);
        verify(metrics).recordMissingGroup();
        verify(metrics).recordDead();
        assertThat(summary.dead()).isEqualTo(1);
    }

    @Test
    void arbitraryNegativeGroupThatDoesNotMatchManagerBindingIsBlocked() {
        prepareClaim(event(-100L, 0), -200L);

        dispatcher.dispatchDue();

        verifyNoInteractions(telegramService);
        verify(store).applyDeliveryOutcomes(List.of(
                WorkloadShadowDeliveryOutcome.dead(
                        event(-100L, 0),
                        0,
                        WorkloadShadowNotificationDispatcher.ERROR_MISSING_GROUP_BINDING,
                        "MISSING_GROUP_BINDING: target должен быть действующей audit-группой менеджера"
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
        prepareClaim(workerGroupEvent, -300L);

        dispatcher.dispatchDue();

        verifyNoInteractions(telegramService);
        verify(store).applyDeliveryOutcomes(List.of(
                WorkloadShadowDeliveryOutcome.dead(
                        workerGroupEvent,
                        0,
                        WorkloadShadowNotificationDispatcher.ERROR_MISSING_GROUP_BINDING,
                        "MISSING_GROUP_BINDING: target должен быть действующей audit-группой менеджера"
                )
        ), NOW, NOW.plusMinutes(5));
    }

    @Test
    void failedTelegramSendIsRetriedWithBackoff() {
        prepareClaim(event(-100L, 0), -100L);
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
        prepareClaim(event(-100L, 7), -100L);
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
    void claimsAndLoadsWholeBatchWithoutPerEventStoreQueries() {
        WorkloadShadowNotificationEvent first = event(-100L, 0);
        WorkloadShadowNotificationEvent second = new WorkloadShadowNotificationEvent(
                2L,
                "INFO",
                "SECOND_EVENT",
                7L,
                "Второе",
                "Сообщение",
                WorkloadShadowNotificationDispatcher.TARGET_MANAGER_AUDIT,
                -100L,
                0
        );
        when(settings.getBoolean(
                WorkloadShadowNotificationDispatcher.GROUP_NOTIFICATIONS_ENABLED,
                true
        )).thenReturn(true);
        List<Long> ids = List.of(1L, 2L);
        when(store.findDueEventIds(NOW, 10)).thenReturn(ids);
        when(store.claim(ids, NOW, NOW.plusMinutes(5))).thenReturn(2);
        when(store.findClaimed(ids, NOW, NOW.plusMinutes(5))).thenReturn(List.of(
                new WorkloadShadowClaimedNotification(first, -100L),
                new WorkloadShadowClaimedNotification(second, -100L)
        ));
        when(telegramService.sendMessage(
                org.mockito.ArgumentMatchers.eq(-100L),
                anyString(),
                org.mockito.ArgumentMatchers.eq("HTML")
        )).thenReturn(true);

        WorkloadShadowNotificationDispatcher.DispatchSummary summary = dispatcher.dispatchDue();

        verify(store).findDueEventIds(NOW, 10);
        verify(store).claim(ids, NOW, NOW.plusMinutes(5));
        verify(store).findClaimed(ids, NOW, NOW.plusMinutes(5));
        verify(store).applyDeliveryOutcomes(List.of(
                WorkloadShadowDeliveryOutcome.sent(first, NOW),
                WorkloadShadowDeliveryOutcome.sent(second, NOW)
        ), NOW, NOW.plusMinutes(5));
        verifyNoMoreInteractions(store);
        assertThat(summary.scanned()).isEqualTo(2);
        assertThat(summary.claimed()).isEqualTo(2);
        assertThat(summary.sent()).isEqualTo(2);
    }

    private void prepareClaim(
            WorkloadShadowNotificationEvent event,
            Long managerAuditGroupChatId
    ) {
        when(settings.getBoolean(
                WorkloadShadowNotificationDispatcher.GROUP_NOTIFICATIONS_ENABLED,
                true
        )).thenReturn(true);
        List<Long> eventIds = List.of(event.id());
        when(store.findDueEventIds(NOW, 10)).thenReturn(eventIds);
        when(store.claim(eventIds, NOW, NOW.plusMinutes(5))).thenReturn(1);
        when(store.findClaimed(eventIds, NOW, NOW.plusMinutes(5))).thenReturn(List.of(
                new WorkloadShadowClaimedNotification(
                        event,
                        managerAuditGroupChatId
                )
        ));
    }

    private WorkloadShadowNotificationEvent event(Long chatId, int attempts) {
        return new WorkloadShadowNotificationEvent(
                1L,
                "WARNING",
                "STAFFING_REQUIRED",
                7L,
                "<Компания>",
                "Нужен сотрудник",
                WorkloadShadowNotificationDispatcher.TARGET_MANAGER_AUDIT,
                chatId,
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
}
