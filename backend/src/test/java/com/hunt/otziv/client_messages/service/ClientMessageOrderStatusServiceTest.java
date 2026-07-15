package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.dto.ClientMessageOrderStatusResponse;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.services.SharedChatLinkSyncService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.whatsapp.service.WhatsAppGroupLinkSyncService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private SharedChatLinkSyncService sharedChatLinkSyncService;

    @Mock
    private WhatsAppGroupLinkSyncService whatsAppGroupLinkSyncService;

    private ClientMessageOrderStatusService service;

    @BeforeEach
    void setUp() {
        service = new ClientMessageOrderStatusService(
                stateRepository,
                appSettingService,
                scheduledClientMessageService,
                companyRepository,
                sharedChatLinkSyncService,
                whatsAppGroupLinkSyncService
        );
    }

    @Test
    void clearsRemarkCardWhenTelegramBindingIsRecoveredAutomatically() {
        OrderDTOList order = order(10L, 1L, "https://t.me/company_chat");
        Company repairedCompany = Company.builder()
                .id(1L)
                .urlChat("https://t.me/company_chat")
                .telegramGroupChatId(100500L)
                .build();
        when(companyRepository.findById(1L)).thenReturn(Optional.of(repairedCompany));
        when(stateRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of());

        service.enrichOrderList(List.of(order));

        assertNull(order.getClientMessageStatus());
        assertEquals(100500L, order.getTelegramGroupChatId());
        verify(sharedChatLinkSyncService).syncSharedChatIds();
        verify(scheduledClientMessageService).ensureClientMessageStateForOrderId(10L);
    }

    @Test
    void keepsManagerRemarkCardWhenTelegramBindingCannotBeRecovered() {
        OrderDTOList order = order(11L, 2L, "https://t.me/company_without_binding");
        Company companyWithoutBinding = Company.builder()
                .id(2L)
                .urlChat("https://t.me/company_without_binding")
                .build();
        when(companyRepository.findById(2L)).thenReturn(Optional.of(companyWithoutBinding));
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
    void attemptsWhatsAppGroupRepairBeforeCreatingManagerRemarkCard() {
        OrderDTOList order = order(12L, 3L, "https://chat.whatsapp.com/invite-code");
        Company companyWithoutBinding = Company.builder()
                .id(3L)
                .urlChat("https://chat.whatsapp.com/invite-code")
                .build();
        when(companyRepository.findById(3L)).thenReturn(Optional.of(companyWithoutBinding));
        when(whatsAppGroupLinkSyncService.repairCompanyLink(companyWithoutBinding))
                .thenReturn(new WhatsAppGroupLinkSyncService.WhatsAppGroupRepairResult(false, "не найдено"));
        when(stateRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of());

        service.enrichOrderList(List.of(order));

        ClientMessageOrderStatusResponse status = order.getClientMessageStatus();
        assertNotNull(status);
        assertEquals("manual_control", status.state());
        assertEquals("whatsapp_group_missing", status.errorCode());
        assertEquals("Контроль: WhatsApp-группа не привязана", status.label());
        verify(sharedChatLinkSyncService).syncSharedChatIds();
        verify(whatsAppGroupLinkSyncService).repairCompanyLink(companyWithoutBinding);
        verify(scheduledClientMessageService, never()).ensureClientMessageStateForOrderId(any());
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
