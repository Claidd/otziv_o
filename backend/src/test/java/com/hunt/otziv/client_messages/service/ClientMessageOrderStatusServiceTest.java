package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.dto.ClientMessageOrderStatusResponse;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientMessageOrderStatusServiceTest {

    @Mock
    private ScheduledClientMessageStateRepository stateRepository;

    @Mock
    private AppSettingService appSettingService;

    @BeforeEach
    void setUpSettings() {
        lenient().when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_ENABLED, true)).thenReturn(true);
        lenient().when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED, true)).thenReturn(true);
        lenient().when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_ENABLED, true)).thenReturn(true);
        lenient().when(appSettingService.getString(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_STATUSES, ScheduledClientMessageService.DEFAULT_REVIEW_CHECK_STATUSES))
                .thenReturn(ScheduledClientMessageService.DEFAULT_REVIEW_CHECK_STATUSES);
        lenient().when(appSettingService.getString(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_STATUSES, ScheduledClientMessageService.DEFAULT_PAYMENT_REMINDER_STATUSES))
                .thenReturn(ScheduledClientMessageService.DEFAULT_PAYMENT_REMINDER_STATUSES);
        lenient().when(appSettingService.getString(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_STATUSES, ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_STATUSES))
                .thenReturn(ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_STATUSES);
        lenient().when(appSettingService.getInt(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_INTERVAL_DAYS, ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS))
                .thenReturn(ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS);
        lenient().when(appSettingService.getInt(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_INTERVAL_DAYS, ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS))
                .thenReturn(ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS);
        lenient().when(appSettingService.getInt(AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_INTERVAL_DAYS, ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_INTERVAL_DAYS))
                .thenReturn(ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_INTERVAL_DAYS);
    }

    @Test
    void enrichOrderListMarksMissingWhatsappGroupAsManualControl() {
        ClientMessageOrderStatusService service = new ClientMessageOrderStatusService(stateRepository, appSettingService);
        OrderDTOList order = OrderDTOList.builder().id(15L).build();
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(3L)
                .orderId(15L)
                .scenario(ClientMessageScenario.CLIENT_TEXT_REMINDER)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastAttemptAt(LocalDateTime.now().minusMinutes(5))
                .updatedAt(LocalDateTime.now())
                .lastErrorCode("whatsapp_group_missing")
                .lastErrorMessage("Для WhatsApp-группы не задан groupId")
                .consecutiveFailures(1)
                .build();

        when(stateRepository.findByOrderIdIn(List.of(15L))).thenReturn(List.of(state));

        service.enrichOrderList(List.of(order));

        ClientMessageOrderStatusResponse status = order.getClientMessageStatus();
        assertNotNull(status);
        assertEquals("manual_control", status.state());
        assertEquals("danger", status.tone());
        assertEquals("Контроль: WhatsApp-группа не привязана", status.label());
        assertEquals("whatsapp_group_missing", status.errorCode());
        verify(stateRepository).findByOrderIdIn(List.of(15L));
    }

    @Test
    void enrichOrderListDoesNotShowScheduledWhenWhatsappGroupIsNotLinkedYet() {
        ClientMessageOrderStatusService service = new ClientMessageOrderStatusService(stateRepository, appSettingService);
        OrderDTOList order = OrderDTOList.builder()
                .id(16L)
                .companyUrlChat("https://chat.whatsapp.com/example")
                .groupId("")
                .build();
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(4L)
                .orderId(16L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .nextAttemptAt(LocalDateTime.now().plusHours(2))
                .updatedAt(LocalDateTime.now())
                .build();

        when(stateRepository.findByOrderIdIn(List.of(16L))).thenReturn(List.of(state));

        service.enrichOrderList(List.of(order));

        ClientMessageOrderStatusResponse status = order.getClientMessageStatus();
        assertNotNull(status);
        assertEquals("manual_control", status.state());
        assertEquals("danger", status.tone());
        assertEquals("Контроль: WhatsApp-группа не привязана", status.label());
        assertEquals("whatsapp_group_missing", status.errorCode());
        verify(stateRepository).findByOrderIdIn(List.of(16L));
    }

    @Test
    void enrichOrderListMarksStaleReviewCheckOrderWithoutQueueStateAsManualControl() {
        ClientMessageOrderStatusService service = new ClientMessageOrderStatusService(stateRepository, appSettingService);
        OrderDTOList order = OrderDTOList.builder()
                .id(17L)
                .status("На проверке")
                .dayToChangeStatusAgo(26)
                .build();

        when(stateRepository.findByOrderIdIn(List.of(17L))).thenReturn(List.of());

        service.enrichOrderList(List.of(order));

        ClientMessageOrderStatusResponse status = order.getClientMessageStatus();
        assertNotNull(status);
        assertEquals("manual_control", status.state());
        assertEquals("danger", status.tone());
        assertEquals("Контроль: автоответчик не создан", status.label());
        assertEquals("client_message_state_missing", status.errorCode());
        verify(stateRepository).findByOrderIdIn(List.of(17L));
    }

    @Test
    void enrichOrderListMarksPublishedOrderWithoutInvoiceRetryAsManualControl() {
        ClientMessageOrderStatusService service = new ClientMessageOrderStatusService(stateRepository, appSettingService);
        OrderDTOList order = OrderDTOList.builder()
                .id(18L)
                .status("Опубликовано")
                .dayToChangeStatusAgo(1)
                .commonInvoice(false)
                .build();

        when(stateRepository.findByOrderIdIn(List.of(18L))).thenReturn(List.of());

        service.enrichOrderList(List.of(order));

        ClientMessageOrderStatusResponse status = order.getClientMessageStatus();
        assertNotNull(status);
        assertEquals("manual_control", status.state());
        assertEquals("danger", status.tone());
        assertEquals("Контроль: счет не поставлен в очередь", status.label());
        assertEquals("payment_invoice_retry_missing", status.errorCode());
        verify(stateRepository).findByOrderIdIn(List.of(18L));
    }

    @Test
    void enrichOrderListPrefersSentStateOverCompletedStateWithOldError() {
        ClientMessageOrderStatusService service = new ClientMessageOrderStatusService(stateRepository, appSettingService);
        OrderDTOList order = OrderDTOList.builder().id(19L).build();
        ScheduledClientMessageState oldCompletedError = ScheduledClientMessageState.builder()
                .id(31L)
                .orderId(19L)
                .scenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY)
                .status(ScheduledMessageStateStatus.DONE)
                .lastAttemptAt(LocalDateTime.now().minusDays(4))
                .updatedAt(LocalDateTime.now().minusDays(4))
                .lastErrorCode("telegram_not_sent")
                .lastErrorMessage("Telegram вернул отказ")
                .consecutiveFailures(1)
                .sentCount(0)
                .build();
        ScheduledClientMessageState sentReminder = ScheduledClientMessageState.builder()
                .id(32L)
                .orderId(19L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .status(ScheduledMessageStateStatus.DONE)
                .lastAttemptAt(LocalDateTime.now().minusMinutes(5))
                .lastSuccessAt(LocalDateTime.now().minusMinutes(5))
                .updatedAt(LocalDateTime.now().minusMinutes(5))
                .sentCount(1)
                .build();

        when(stateRepository.findByOrderIdIn(List.of(19L))).thenReturn(List.of(oldCompletedError, sentReminder));

        service.enrichOrderList(List.of(order));

        ClientMessageOrderStatusResponse status = order.getClientMessageStatus();
        assertNotNull(status);
        assertEquals("sent", status.state());
        assertEquals("success", status.tone());
        assertEquals("Автоответчик отправил", status.label());
        assertEquals("PAYMENT_REMINDER", status.scenario());
        verify(stateRepository).findByOrderIdIn(List.of(19L));
    }

    @Test
    void enrichOrderListSkipsSyntheticCommonInvoiceCards() {
        ClientMessageOrderStatusService service = new ClientMessageOrderStatusService(stateRepository, appSettingService);

        service.enrichOrderList(List.of(OrderDTOList.builder().id(-101L).build()));

        verifyNoInteractions(stateRepository);
    }
}
