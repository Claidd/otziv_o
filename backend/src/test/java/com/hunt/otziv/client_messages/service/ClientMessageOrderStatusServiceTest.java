package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.dto.ClientMessageOrderStatusResponse;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ClientMessageOrderStatusServiceTest {

    @Mock
    private ScheduledClientMessageStateRepository stateRepository;

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private ScheduledClientMessageService scheduledClientMessageService;

    private ClientMessageOrderStatusService service;

    @BeforeEach
    void setUp() {
        service = new ClientMessageOrderStatusService(
                stateRepository,
                appSettingService,
                scheduledClientMessageService
        );
    }

    @Test
    void reportsMissingTelegramBindingWithoutTryingToRepairItDuringBoardLoad() {
        OrderDTOList order = order(10L, 1L, "https://t.me/company_chat");
        when(stateRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of());

        service.enrichOrderList(List.of(order));

        ClientMessageOrderStatusResponse status = order.getClientMessageStatus();
        assertNotNull(status);
        assertEquals("manual_control", status.state());
        assertEquals("danger", status.tone());
        assertEquals("telegram_group_missing", status.errorCode());
        assertEquals("Контроль: Telegram-группа не привязана", status.label());
        verify(scheduledClientMessageService, never()).ensureClientMessageStateForOrderId(any());
    }

    @Test
    void reportsMissingWhatsAppBindingWithoutWaitingForGatewayDuringBoardLoad() {
        OrderDTOList order = order(12L, 3L, "https://chat.whatsapp.com/invite-code");
        when(stateRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of());

        service.enrichOrderList(List.of(order));

        ClientMessageOrderStatusResponse status = order.getClientMessageStatus();
        assertNotNull(status);
        assertEquals("manual_control", status.state());
        assertEquals("whatsapp_group_missing", status.errorCode());
        assertEquals("Контроль: WhatsApp-группа не привязана", status.label());
        verify(scheduledClientMessageService, never()).ensureClientMessageStateForOrderId(any());
    }

    @Test
    void marksMissingBindingAsNotRequiredWhileCompanyIsStopped() {
        OrderDTOList order = order(17L, 4L, "https://chat.whatsapp.com/invite-code");
        order.setCompanyStatus("На стопе");
        when(stateRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of());

        service.enrichOrderList(List.of(order));

        ClientMessageOrderStatusResponse status = order.getClientMessageStatus();
        assertNotNull(status);
        assertEquals("not_required", status.state());
        assertEquals("muted", status.tone());
        assertEquals("Привязка чата пока не требуется", status.label());
        assertNull(status.errorCode());
        verify(scheduledClientMessageService, never()).recoverMissingClientMessageStateForOrderId(any());
    }

    @Test
    void bindingCheckReturnsImmediatelyAfterCompanyLeavesBan() {
        OrderDTOList order = order(18L, 5L, "https://chat.whatsapp.com/invite-code");
        order.setCompanyStatus("В работе");
        when(stateRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of());

        service.enrichOrderList(List.of(order));

        ClientMessageOrderStatusResponse status = order.getClientMessageStatus();
        assertNotNull(status);
        assertEquals("manual_control", status.state());
        assertEquals("whatsapp_group_missing", status.errorCode());
    }

    @Test
    void keepsTemporaryDeliveryErrorInRetryQueueBeforeManualThreshold() {
        OrderDTOList order = order(13L, null, null);
        ScheduledClientMessageState state = activeState(13L)
                .lastErrorCode("telegram_send_timeout")
                .lastErrorMessage("Временная ошибка Telegram")
                .consecutiveFailures(2)
                .lastAttemptAt(LocalDateTime.now().minusMinutes(10))
                .nextAttemptAt(LocalDateTime.now().plusMinutes(5))
                .build();
        when(stateRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of(state));
        stubManualControlSettings(3, 60);

        service.enrichOrderList(List.of(order));

        ClientMessageOrderStatusResponse status = order.getClientMessageStatus();
        assertNotNull(status);
        assertEquals("scheduled", status.state());
        assertEquals("wait", status.tone());
        assertEquals("Автоответчик запланирован", status.label());
        assertEquals("telegram_send_timeout", status.errorCode());
    }

    @Test
    void escalatesTemporaryDeliveryErrorAfterManualFailureThreshold() {
        OrderDTOList order = order(14L, null, null);
        ScheduledClientMessageState state = activeState(14L)
                .lastErrorCode("telegram_send_timeout")
                .lastErrorMessage("Временная ошибка Telegram")
                .consecutiveFailures(3)
                .lastAttemptAt(LocalDateTime.now().minusMinutes(10))
                .nextAttemptAt(LocalDateTime.now().plusMinutes(5))
                .build();
        when(stateRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of(state));
        stubManualControlSettings(3, 60);

        service.enrichOrderList(List.of(order));

        ClientMessageOrderStatusResponse status = order.getClientMessageStatus();
        assertNotNull(status);
        assertEquals("manual_control", status.state());
        assertEquals("danger", status.tone());
        assertEquals("Контроль: автоответчик не отправил", status.label());
        assertEquals("telegram_send_timeout", status.errorCode());
    }

    @Test
    void showsReviewRecoveryAsNeutralWaitingStateWithoutManagerControl() {
        OrderDTOList order = order(15L, null, null);
        ScheduledClientMessageState state = activeState(15L)
                .lastErrorCode("review_recovery_active")
                .lastErrorMessage("Отправка продолжится автоматически после завершения восстановления")
                .consecutiveFailures(99)
                .lastAttemptAt(LocalDateTime.now().minusHours(5))
                .nextAttemptAt(LocalDateTime.now().plusMinutes(10))
                .build();
        when(stateRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of(state));

        service.enrichOrderList(List.of(order));

        ClientMessageOrderStatusResponse status = order.getClientMessageStatus();
        assertNotNull(status);
        assertEquals("waiting_recovery", status.state());
        assertEquals("wait", status.tone());
        assertEquals("Ждём восстановления отзывов", status.label());
        assertEquals("review_recovery_active", status.errorCode());
    }

    @Test
    void showsRateLimitedQueueAsNeutralWaitingStateWithoutManagerControl() {
        OrderDTOList order = order(16L, null, null);
        LocalDateTime nextAttemptAt = LocalDateTime.now().plusMinutes(20);
        ScheduledClientMessageState state = activeState(16L)
                .lastErrorCode("rate_limited")
                .lastErrorMessage("Следующий слот отправки: " + nextAttemptAt)
                .consecutiveFailures(99)
                .lastAttemptAt(LocalDateTime.now().minusHours(5))
                .nextAttemptAt(nextAttemptAt)
                .sentCount(1)
                .build();
        when(stateRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of(state));
        when(scheduledClientMessageService.effectiveNextAttemptAt(nextAttemptAt)).thenReturn(nextAttemptAt);

        service.enrichOrderList(List.of(order));

        ClientMessageOrderStatusResponse status = order.getClientMessageStatus();
        assertNotNull(status);
        assertEquals("scheduled", status.state());
        assertEquals("wait", status.tone());
        assertEquals("Ожидает отправки", status.label());
        assertEquals("rate_limited", status.errorCode());
        assertEquals(nextAttemptAt, status.nextAttemptAt());
    }

    @Test
    void ignoresClientTextReminderFromPreviousWaitingCycle() {
        LocalDateTime currentCycle = LocalDateTime.of(2026, 7, 24, 20, 37, 52);
        OrderDTOList order = order(25_442L, null, null);
        order.setStatus("Новый");
        order.setWaitingForClient(true);
        order.setWaitingForClientChangedAt(currentCycle);
        order.setDayToChangeStatusAgo(30);

        ScheduledClientMessageState staleState = activeState(25_442L)
                .scenario(ClientMessageScenario.CLIENT_TEXT_REMINDER)
                .targetKey("client-text:25442:2026-07-18T14:50:19")
                .lastErrorCode("rate_limited")
                .nextAttemptAt(LocalDateTime.of(2026, 7, 24, 20, 54, 6))
                .build();
        when(stateRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of(staleState));

        service.enrichOrderList(List.of(order));

        assertNull(order.getClientMessageStatus());
        verify(scheduledClientMessageService, never()).recoverMissingClientMessageStateForOrderId(any());
        verify(scheduledClientMessageService, never()).effectiveNextAttemptAt(any());
    }

    @Test
    void usesOnlyClientTextReminderFromCurrentWaitingCycle() {
        LocalDateTime currentCycle = LocalDateTime.of(2026, 7, 24, 20, 37, 52);
        LocalDateTime nextAttemptAt = LocalDateTime.of(2026, 7, 27, 10, 0);
        OrderDTOList order = order(25_442L, null, null);
        order.setStatus("Новый");
        order.setWaitingForClient(true);
        order.setWaitingForClientChangedAt(currentCycle);

        ScheduledClientMessageState staleState = activeState(25_442L)
                .scenario(ClientMessageScenario.CLIENT_TEXT_REMINDER)
                .targetKey("client-text:25442:2026-07-18T14:50:19")
                .lastErrorCode("missing_group_id")
                .build();
        ScheduledClientMessageState currentState = activeState(25_442L)
                .scenario(ClientMessageScenario.CLIENT_TEXT_REMINDER)
                .targetKey("client-text:25442:2026-07-24T20:37:52")
                .nextAttemptAt(nextAttemptAt)
                .build();
        when(stateRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of(staleState, currentState));

        service.enrichOrderList(List.of(order));

        assertNotNull(order.getClientMessageStatus());
        assertEquals("scheduled", order.getClientMessageStatus().state());
        assertEquals(nextAttemptAt, order.getClientMessageStatus().nextAttemptAt());
    }

    private OrderDTOList order(Long orderId, Long companyId, String companyUrlChat) {
        return OrderDTOList.builder()
                .id(orderId)
                .companyId(companyId)
                .companyUrlChat(companyUrlChat)
                .build();
    }

    private ScheduledClientMessageState.ScheduledClientMessageStateBuilder activeState(Long orderId) {
        return ScheduledClientMessageState.builder()
                .id(orderId)
                .scenario(ClientMessageScenario.REVIEW_CHECK_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey(String.valueOf(orderId))
                .orderId(orderId)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .updatedAt(LocalDateTime.now());
    }

    private void stubManualControlSettings(int failureThreshold, int afterMinutes) {
        when(appSettingService.getInt(
                eq(AppSettingService.CLIENT_MESSAGES_MANUAL_CONTROL_FAILURE_THRESHOLD),
                anyInt()
        )).thenReturn(failureThreshold);
        lenient().when(appSettingService.getInt(
                eq(AppSettingService.CLIENT_MESSAGES_MANUAL_CONTROL_AFTER_MINUTES),
                anyInt()
        )).thenReturn(afterMinutes);
    }
}
