package com.hunt.otziv.client_messages;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.OrderPaymentMessageBuilder;
import com.hunt.otziv.p_products.status.OrderReviewCheckMessageBuilder;
import com.hunt.otziv.p_products.status.OrderStatusNotificationService;
import com.hunt.otziv.p_products.status.OrderStatusTransitionService;
import com.hunt.otziv.payments.PaymentLinkService;
import com.hunt.otziv.u_users.model.Manager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledClientMessageServiceTest {

    @Mock
    private ScheduledClientMessageStateRepository stateRepository;
    @Mock
    private ScheduledClientMessageAttemptRepository attemptRepository;
    @Mock
    private ArchiveCompanyMessageCandidateRepository archiveCandidateRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private ClientChatMessageSender messageSender;
    @Mock
    private ClientMessageSlotPlanner slotPlanner;
    @Mock
    private OrderStatusTransitionService orderStatusTransitionService;
    @Mock
    private OrderStatusNotificationService orderStatusNotificationService;
    @Mock
    private OrderPaymentMessageBuilder orderPaymentMessageBuilder;
    @Mock
    private PaymentLinkService paymentLinkService;
    @Mock
    private OrderReviewCheckMessageBuilder reviewCheckMessageBuilder;
    @Mock
    private BadReviewTaskService badReviewTaskService;
    @Mock
    private PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;

    @InjectMocks
    private ScheduledClientMessageService service;

    @Test
    void liveDisabledRecordsDryRunSkipWithoutFailureOrSenderCall() {
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(77L)
                .scenario(ClientMessageScenario.REVIEW_CHECK_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:10:2026-05-20T10:00")
                .companyId(20L)
                .orderId(10L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .consecutiveFailures(3)
                .build();
        Company company = new Company();
        Manager manager = new Manager();
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 0, 20);

        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(false);
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_BUSINESS_WINDOWS,
                ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC
        )).thenReturn(ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(service, "sendMessage", state, company, manager, "message", now, 2);

        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        ScheduledClientMessageAttempt attempt = attemptCaptor.getValue();
        assertEquals(ScheduledMessageAttemptStatus.SKIPPED, attempt.getStatus());
        assertEquals("client_messages_dry_run", attempt.getErrorCode());
        assertEquals("Live-отправка выключена настройкой; сообщение не отправлено", attempt.getErrorMessage());
        assertEquals("message", attempt.getMessagePreview());

        verify(messageSender, never()).send(any(), any(), any(), any());
        verify(attemptRepository, never()).countByStatusAndAttemptedAtGreaterThanEqual(any(), any());
        verify(appSettingService, never()).setString(eq(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL), anyString());

        assertEquals(now, state.getLastAttemptAt());
        assertNull(state.getLastErrorCode());
        assertNull(state.getLastErrorMessage());
        assertEquals(0, state.getConsecutiveFailures());
        assertNull(state.getLockedUntil());
        assertNotNull(state.getNextAttemptAt());
        verify(stateRepository).save(state);
    }

    @Test
    void paymentReminderMovesFirstInvoiceToReminderStatusAfterSend() throws Exception {
        LocalDateTime statusChangedAt = LocalDateTime.of(2026, 5, 20, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 22, 10, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(78L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:15:2026-05-20T10:00")
                .companyId(25L)
                .orderId(15L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(25L);
        company.setGroupId("group-15");
        Manager manager = new Manager();
        manager.setClientId("client-15");
        manager.setPayText("Оплатите, пожалуйста.");
        Order order = new Order();
        order.setId(15L);
        order.setCompany(company);
        order.setManager(manager);
        order.setStatus(OrderStatus.builder().title("Выставлен счет").build());
        order.setStatusChangedAt(statusChangedAt);

        when(orderRepository.findByIdForMutation(15L)).thenReturn(java.util.Optional.of(order));
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_TEXT,
                ScheduledClientMessageService.DEFAULT_PAYMENT_REMINDER_TEXT
        )).thenReturn(ScheduledClientMessageService.DEFAULT_PAYMENT_REMINDER_TEXT);
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(messageSender.send(eq(company), eq("client-15"), eq("group-15"), anyString()))
                .thenReturn(ClientMessageSendResult.sent("WhatsApp"));
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderStatusTransitionService.changeStatusForOrder(15L, "Напоминание")).thenReturn(true);

        ReflectionTestUtils.invokeMethod(
                service,
                "sendOrderReminder",
                state,
                company,
                java.util.List.of("Выставлен счет", "Напоминание"),
                "Заказ уже не ожидает оплату",
                2,
                false,
                now
        );

        verify(messageSender).send(eq(company), eq("client-15"), eq("group-15"), anyString());
        verify(orderStatusTransitionService).changeStatusForOrder(15L, "Напоминание");
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
    }

    @Test
    void clientTextReminderSendsOnlyForCurrentWaitingCycle() {
        LocalDateTime waitingChangedAt = LocalDateTime.of(2026, 5, 20, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 23, 10, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(79L)
                .scenario(ClientMessageScenario.CLIENT_TEXT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("client-text:16:2026-05-20T10:00")
                .companyId(26L)
                .orderId(16L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(26L);
        company.setGroupId("group-16");
        Manager manager = new Manager();
        manager.setClientId("client-16");
        Order order = new Order();
        order.setId(16L);
        order.setCompany(company);
        order.setManager(manager);
        order.setStatus(OrderStatus.builder().title("Новый").build());
        order.setWaitingForClient(true);
        order.setWaitingForClientChangedAt(waitingChangedAt);

        when(orderRepository.findByIdForMutation(16L)).thenReturn(java.util.Optional.of(order));
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_STATUSES,
                ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_STATUSES
        )).thenReturn(ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_STATUSES);
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_TEXT,
                ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_TEXT
        )).thenReturn(ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_REMINDER_TEXT);
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(messageSender.send(eq(company), eq("client-16"), eq("group-16"), anyString()))
                .thenReturn(ClientMessageSendResult.sent("WhatsApp"));
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(service, "sendClientTextReminder", state, company, now);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageSender).send(eq(company), eq("client-16"), eq("group-16"), messageCaptor.capture());
        assertTrue(messageCaptor.getValue().contains("заказу №16"));
        assertEquals(ScheduledMessageStateStatus.ACTIVE, state.getStatus());
        assertNotNull(state.getNextAttemptAt());
    }

    @Test
    void liveDisabledClearsLegacyPauseCreatedByDryRunFailures() {
        LocalDateTime pausedUntil = LocalDateTime.of(2026, 5, 25, 1, 2);
        String reason = "За 10 мин. накоплено 20 ошибок. Последняя: client_messages_live_disabled - Авторассылка выключена настройкой";

        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(false);
        when(appSettingService.getString(AppSettingService.CLIENT_MESSAGES_PAUSE_REASON, "")).thenReturn(reason);

        Boolean cleared = ReflectionTestUtils.invokeMethod(service, "clearLegacyDryRunPause", pausedUntil);

        assertTrue(Boolean.TRUE.equals(cleared));
        verify(appSettingService).setString(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL, "");
        verify(appSettingService).setString(AppSettingService.CLIENT_MESSAGES_PAUSE_REASON, "");
    }

    @Test
    void retryPaymentInvoiceSendsInvoiceAndCompletesStateWhenClientMessageSucceeds() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 5, 25, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 12, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(90L)
                .scenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:10:2026-05-25T10:00")
                .companyId(20L)
                .orderId(10L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(20L);
        company.setGroupId("group-10");
        Manager manager = new Manager();
        manager.setClientId("client-10");
        Order order = new Order();
        order.setId(10L);
        order.setCompany(company);
        order.setManager(manager);
        order.setStatus(OrderStatus.builder().title("Опубликовано").build());
        order.setStatusChangedAt(changedAt);

        when(orderRepository.findByIdForMutation(10L)).thenReturn(java.util.Optional.of(order));
        when(orderPaymentMessageBuilder.publishedOrderPaymentMessage(order)).thenReturn("финальный счет");
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderStatusNotificationService.sendMessageToClientChat(
                any(),
                eq(order),
                any(),
                any(),
                any(),
                any()
        )).thenReturn("Выставлен счет");

        ReflectionTestUtils.invokeMethod(service, "retryPaymentInvoice", state, company, now);

        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SENT, attemptCaptor.getValue().getStatus());
        assertEquals(ClientMessageScenario.PAYMENT_INVOICE_RETRY, attemptCaptor.getValue().getScenario());
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        verify(stateRepository, org.mockito.Mockito.atLeastOnce()).save(state);
    }

    @Test
    void retryReviewCheckDeliverySendsReviewLinkAndCompletesStateWhenClientMessageSucceeds() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 5, 25, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 12, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(91L)
                .scenario(ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:11:2026-05-25T10:00")
                .companyId(21L)
                .orderId(11L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(21L);
        company.setGroupId("group-11");
        Manager manager = new Manager();
        manager.setClientId("client-11");
        Order order = new Order();
        order.setId(11L);
        order.setCompany(company);
        order.setManager(manager);
        order.setStatus(OrderStatus.builder().title("В проверку").build());
        order.setStatusChangedAt(changedAt);

        when(orderRepository.findByIdForMutation(11L)).thenReturn(java.util.Optional.of(order));
        when(reviewCheckMessageBuilder.reviewCheckMessage(order)).thenReturn("ссылка на проверку");
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderStatusNotificationService.sendMessageToClientChat(
                eq("В проверку"),
                eq(order),
                eq("client-11"),
                eq("group-11"),
                eq("ссылка на проверку"),
                eq("На проверке")
        )).thenReturn("На проверке");

        ReflectionTestUtils.invokeMethod(service, "retryReviewCheckDelivery", state, company, now);

        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SENT, attemptCaptor.getValue().getStatus());
        assertEquals(ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY, attemptCaptor.getValue().getScenario());
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        verify(stateRepository, org.mockito.Mockito.atLeastOnce()).save(state);
    }

    @Test
    void retryBadReviewInvoiceSendsInvoiceAndCompletesStateWhenClientMessageSucceeds() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 12, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(92L)
                .scenario(ClientMessageScenario.BAD_REVIEW_INVOICE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-invoice:order:12")
                .companyId(22L)
                .orderId(12L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Company company = new Company();
        company.setId(22L);
        company.setGroupId("group-12");
        Manager manager = new Manager();
        manager.setClientId("client-12");
        Order order = new Order();
        order.setId(12L);
        order.setCompany(company);
        order.setManager(manager);
        order.setStatus(OrderStatus.builder().title("Не оплачено").build());

        when(orderRepository.findByIdForMutation(12L)).thenReturn(java.util.Optional.of(order));
        when(badReviewTaskService.buildBadReviewInvoiceMessage(order)).thenReturn("счет после плохого");
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(orderStatusNotificationService.sendInformationalMessageToClientChat(
                eq(order),
                eq("client-12"),
                eq("group-12"),
                eq("счет после плохого"),
                eq("счет после плохого отзыва")
        )).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "retryBadReviewInvoice", state, company, now);

        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SENT, attemptCaptor.getValue().getStatus());
        assertEquals(ClientMessageScenario.BAD_REVIEW_INVOICE, attemptCaptor.getValue().getScenario());
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        verify(stateRepository, org.mockito.Mockito.atLeastOnce()).save(state);
    }

    @Test
    void autoArchiveStaleReviewCheckChangesOrderStatusAndCompletesState() throws Exception {
        LocalDateTime changedAt = LocalDateTime.of(2026, 4, 20, 10, 0);
        LocalDateTime now = LocalDateTime.of(2026, 5, 25, 12, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(93L)
                .scenario(ClientMessageScenario.REVIEW_CHECK_AUTO_ARCHIVE)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:13:2026-04-20T10:00")
                .companyId(23L)
                .orderId(13L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Order order = new Order();
        order.setId(13L);
        order.setStatus(OrderStatus.builder().title("На проверке").build());
        order.setStatusChangedAt(changedAt);

        when(orderRepository.findByIdForMutation(13L)).thenReturn(java.util.Optional.of(order));
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_STATUSES,
                ScheduledClientMessageService.DEFAULT_REVIEW_CHECK_STATUSES
        )).thenReturn(ScheduledClientMessageService.DEFAULT_REVIEW_CHECK_STATUSES);
        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_AUTO_ARCHIVE_DAYS,
                ScheduledClientMessageService.DEFAULT_REVIEW_CHECK_AUTO_ARCHIVE_DAYS
        )).thenReturn(30);
        when(orderStatusTransitionService.changeStatusForOrder(13L, "Архив")).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "autoArchiveStaleReviewCheck", state, now);

        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SENT, attemptCaptor.getValue().getStatus());
        assertEquals(ClientMessageScenario.REVIEW_CHECK_AUTO_ARCHIVE, attemptCaptor.getValue().getScenario());
        assertEquals("system", attemptCaptor.getValue().getChannel());
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        verify(stateRepository, org.mockito.Mockito.atLeastOnce()).save(state);
    }

    @Test
    void autoBanAfterBadReviewsMovesOrderToBanWhenFinalInvoiceStillUnpaid() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 28, 12, 0);
        ScheduledClientMessageState state = ScheduledClientMessageState.builder()
                .id(94L)
                .scenario(ClientMessageScenario.BAD_REVIEW_AUTO_BAN)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("bad-review-auto-ban:order:14")
                .companyId(24L)
                .orderId(14L)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .build();
        Order order = new Order();
        order.setId(14L);
        order.setStatus(OrderStatus.builder().title("Не оплачено").build());

        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_ENABLED, true)).thenReturn(true);
        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_DELAY_DAYS,
                ScheduledClientMessageService.DEFAULT_BAD_REVIEW_AUTO_BAN_DELAY_DAYS
        )).thenReturn(2);
        when(orderRepository.findByIdForMutation(14L)).thenReturn(java.util.Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(14L))
                .thenReturn(new BadReviewTaskSummary(2, 0, 2, 0, BigDecimal.valueOf(600), BigDecimal.ZERO));
        when(orderStatusTransitionService.changeStatusForOrder(14L, "Бан")).thenReturn(true);

        ReflectionTestUtils.invokeMethod(service, "autoBanAfterBadReviews", state, now);

        ArgumentCaptor<ScheduledClientMessageAttempt> attemptCaptor = ArgumentCaptor.forClass(ScheduledClientMessageAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertEquals(ScheduledMessageAttemptStatus.SENT, attemptCaptor.getValue().getStatus());
        assertEquals(ClientMessageScenario.BAD_REVIEW_AUTO_BAN, attemptCaptor.getValue().getScenario());
        assertEquals("system", attemptCaptor.getValue().getChannel());
        assertEquals(ScheduledMessageStateStatus.DONE, state.getStatus());
        assertNull(state.getNextAttemptAt());
        verify(orderStatusTransitionService).changeStatusForOrder(14L, "Бан");
        verify(stateRepository, org.mockito.Mockito.atLeastOnce()).save(state);
    }
}
