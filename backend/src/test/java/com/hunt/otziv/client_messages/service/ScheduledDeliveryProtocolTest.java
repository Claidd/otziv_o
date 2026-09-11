package com.hunt.otziv.client_messages.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.model.*;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderStatusNotificationService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.Manager;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ScheduledDeliveryProtocolTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 10, 0);
    private ScheduledClientMessageService service;
    private ScheduledClientMessageStateRepository states;
    private ClientChatMessageSender sender;
    private ScheduledClientMessageState state;
    private Company company;
    private Manager manager;

    @BeforeEach void setup() throws Exception {
        service = serviceWithMockDependencies();
        states = dependency(service, "stateRepository", ScheduledClientMessageStateRepository.class);
        sender = dependency(service, "messageSender", ClientChatMessageSender.class);
        var runner = dependency(service, "transactionRunner", ClientMessageTransactionRunner.class);
        when(runner.callInNewTransaction(any())).thenAnswer(inv -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { return ((Supplier<?>) inv.getArgument(0)).get(); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        });
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(runner).runInNewTransaction(any());
        when(runner.callInPreparationTransaction(any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        state = ScheduledClientMessageState.builder().id(71L).scenario(ClientMessageScenario.ARCHIVE_REORDER_OFFER)
                .targetType(ClientMessageTargetType.ARCHIVE_COMPANY).targetKey("archive:7:cycle:1")
                .status(ScheduledMessageStateStatus.ACTIVE).build();
        company = Company.builder().id(7L).title("Original company").urlChat("https://t.me/company")
                .telegramGroupChatId(-1007L).groupId("old-group").build();
        manager = Manager.builder().id(3L).clientId("original-client").build();
        when(dependency(service, "slotPlanner", ClientMessageSlotPlanner.class).nextAllowedAt(any(), nullable(String.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(states.findByIdForUpdate(71L)).thenReturn(Optional.of(state));
    }

    static ScheduledClientMessageService serviceWithMockDependencies() throws Exception {
        var constructor = Arrays.stream(ScheduledClientMessageService.class.getConstructors())
                .max(Comparator.comparingInt(java.lang.reflect.Constructor::getParameterCount)).orElseThrow();
        var instance = (ScheduledClientMessageService) constructor.newInstance(Arrays.stream(constructor.getParameterTypes())
                .map(type -> mock(type)).toArray());
        when(dependency(instance, "appSettingService", AppSettingService.class)
                .getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        installRecovery(instance);
        return instance;
    }

    static void installRecovery(ScheduledClientMessageService instance) {
        ReflectionTestUtils.setField(instance, "deliveryRecovery", new ScheduledDeliveryRecovery(
                dependency(instance, "stateRepository", ScheduledClientMessageStateRepository.class),
                dependency(instance, "transactionRunner", ClientMessageTransactionRunner.class),
                dependency(instance, "messageSender", ClientChatMessageSender.class)));
    }

    static <T> T dependency(Object service, String name, Class<T> type) {
        return type.cast(ReflectionTestUtils.getField(service, name));
    }

    private ScheduledClientMessageService.PreparedScheduledDelivery prepare(String message, String copy) {
        return ReflectionTestUtils.invokeMethod(service, "persistScheduledDelivery", state, company, manager,
                message, copy, 14, null, null, null, null, NOW);
    }

    @Test void knownUnsentRetryKeepsTheExactRecipientMoneyCopyAndOperation() {
        var first = prepare("К оплате: 1200 руб.", "+79991112233");
        when(sender.deliverWithOperationId(any(), any(), any(), any(), any(), any()))
                .thenReturn(ClientMessageSendResult.failed("gateway_not_ready", "before dispatch"));
        service.dispatchScheduledDelivery(first, NOW);
        assertThat(state.getDeliveryStatus()).isEqualTo("RETRYABLE");
        company.setTelegramGroupChatId(-1009L);
        company.setGroupId("new-group");
        manager.setClientId("new-client");
        var retry = prepare("К оплате: 9999 руб.", "+79994445566");
        assertThat(retry).isEqualTo(first);
        assertThat(state.getDeliveryEnvelope()).contains("1200", "original-client", "old-group", "+79991112233")
                .doesNotContain("9999", "new-client", "new-group", "+79994445566");
    }

    @Test void liveOffBetweenPreparationAndDispatchPausesAndResumesTheSameFrozenMessage() {
        var prepared = prepare("К оплате: 1200 руб.", "+79991112233");
        var settings = dependency(service, "appSettingService", AppSettingService.class);
        when(settings.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(false);
        service.dispatchScheduledDelivery(prepared, NOW);
        assertThat(state.getDeliveryStatus()).isEqualTo("RETRYABLE");
        assertThat(state.getDeliveryToken()).isEqualTo(prepared.token());
        assertThat(state.getNextAttemptAt()).isNotNull();
        assertThat(state.getSentCount()).isZero();
        verifyNoInteractions(sender);
        verify(settings, never()).getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true);
        company.setTelegramGroupChatId(-1009L);
        manager.setClientId("replacement");
        when(settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        var resumed = prepare("9999 руб.", "+79994445566");
        assertThat(resumed).isEqualTo(prepared);
        when(sender.deliverWithOperationId(any(), any(), any(), any(), any(), any())).thenReturn(ClientMessageSendResult.sent("Telegram", "42"));
        service.dispatchScheduledDelivery(resumed, NOW);
        verify(sender).deliverWithOperationId(eq(prepared.target()), eq(prepared.clientId()), eq(prepared.groupId()),
                eq("К оплате: 1200 руб."), any(), eq(prepared.operationId()));
        assertThat(state.getSentCount()).isEqualTo(1);
    }

    @Test void unavailableFreshSwitchReadProvesNoDispatchAndKeepsRetryableSnapshot() {
        var prepared = prepare("message", null);
        when(dependency(service, "appSettingService", AppSettingService.class)
                .getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("settings unavailable"));
        service.dispatchScheduledDelivery(prepared, NOW);
        assertThat(state.getDeliveryStatus()).isEqualTo("RETRYABLE");
        assertThat(state.getDeliveryEnvelope()).contains("message");
        assertThat(state.getDeliveryToken()).isEqualTo(prepared.token());
        assertThat(state.getLastErrorCode()).isNull();
        verifyNoInteractions(sender);
    }

    @Test void preparationUsesFreshOffEvenWhenCachedLiveValueIsOn() {
        var settings = dependency(service, "appSettingService", AppSettingService.class);
        when(settings.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(true);
        when(settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)).thenReturn(false);
        Object prepared = ReflectionTestUtils.invokeMethod(service, "sendMessage", state, company, manager, "message", NOW, 3);
        assertThat(prepared).isNull();
        assertThat(state.getDeliveryEnvelope()).isNull();
        verify(settings, never()).getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true);
        verifyNoInteractions(sender);
    }

    @Test void badInvoiceOffAfterPrepareResumesItsOriginalMoneyRecipientAndOperation() {
        state.setScenario(ClientMessageScenario.BAD_REVIEW_INVOICE);
        state.setTargetKey("bad-review-invoice:order:5");
        state.setOrderId(5L);
        state.setCompanyId(7L);
        var order = Order.builder().id(5L).company(company).manager(manager)
                .status(OrderStatus.builder().title("Не оплачено").build()).build();
        var settings = dependency(service, "appSettingService", AppSettingService.class);
        when(settings.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true)).thenReturn(true);
        when(settings.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true)).thenReturn(true);
        when(settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true))
                .thenReturn(true, true, false, true, true, true);
        when(states.findByScenarioAndTargetKey(ClientMessageScenario.BAD_REVIEW_INVOICE, state.getTargetKey())).thenReturn(Optional.of(state));
        when(states.findById(71L)).thenReturn(Optional.of(state));
        when(states.lockActiveState(eq(71L), any(), any(), any(), any())).thenAnswer(inv -> {
            state.setDeliveryStatus(null);
            state.setLockedUntil(inv.getArgument(2));
            state.setLastErrorCode(ClientMessageStateSafety.TRANSACTION_IN_PROGRESS);
            return 1;
        });
        when(dependency(service, "orderRepository", OrderRepository.class).findByIdForMutation(5L)).thenReturn(Optional.of(order));
        when(dependency(service, "companyRepository", com.hunt.otziv.c_companies.repository.CompanyRepository.class)
                .findByIdForCompanyDto(7L)).thenReturn(Optional.of(company));
        var builder = dependency(service, "orderPaymentMessageBuilder", com.hunt.otziv.p_products.status.service.OrderPaymentMessageBuilder.class);
        when(builder.publishedOrderPaymentMessageWithTransfer(order)).thenReturn(
                new com.hunt.otziv.p_products.status.service.OrderPaymentMessageBuilder.PreparedPaymentMessage("1200 руб.", "+79991112233"));
        service.deliverBadReviewInvoiceImmediately(9L, 5L);
        String token = state.getDeliveryToken();
        assertThat(state.getDeliveryStatus()).isEqualTo("RETRYABLE");
        assertThat(state.getDeliveryEnvelope()).contains("1200 руб.", "original-client", "+79991112233");
        verifyNoInteractions(sender);
        company.setTelegramGroupChatId(-1009L);
        manager.setClientId("replacement");
        when(sender.deliverWithOperationId(any(), any(), any(), any(), any(), any())).thenReturn(ClientMessageSendResult.sent("Telegram", "42"));
        service.deliverBadReviewInvoiceImmediately(9L, 5L);
        verify(builder, times(1)).publishedOrderPaymentMessageWithTransfer(order);
        verify(sender).deliverWithOperationId(argThat(target -> target.telegramChatId().equals(-1007L)),
                eq("original-client"), eq("old-group"), eq("1200 руб."), any(),
                eq(com.hunt.otziv.whatsapp.service.WhatsAppOperationKey.of("bad-review-invoice-v1", 71L, token)));
        assertThat(state.getStatus()).isEqualTo(ScheduledMessageStateStatus.DONE);
        assertThat(state.getSentCount()).isEqualTo(1);
    }

    @Test void missingReceiptAfterCrashHoldsUnknownWithoutSendingAndRotatesRecovery() {
        var prepared = prepare("message", null);
        when(states.findRecoverablePreparedIds(any(), any())).thenReturn(List.of(71L));
        when(sender.recordedOutcome(prepared.operationId())).thenReturn(ClientMessageOperationFence.unknown());
        ReflectionTestUtils.invokeMethod(service, "recoverOrdinaryDeliveries", NOW.plusMinutes(6));
        assertThat(state.getDeliveryStatus()).isEqualTo("UNKNOWN");
        assertThat(state.getNextAttemptAt()).isNull();
        assertThat(state.getDeliveryRecoveryCheckedAt()).isEqualTo(NOW.plusMinutes(6));
        assertThat(state.getDeliveryToken()).isEqualTo(prepared.token());
        assertThat(state.getSentCount()).isZero();
        verify(sender, never()).deliverWithOperationId(any(), any(), any(), any(), any(), any());
    }

    @Test void confirmedReceiptAfterRestartFinalizesOnceWithoutSending() {
        var prepared = prepare("message", null);
        when(states.findRecoverablePreparedIds(any(), any())).thenReturn(List.of(71L));
        when(sender.recordedOutcome(prepared.operationId())).thenReturn(ClientMessageSendResult.sent("Telegram", "42"));
        ReflectionTestUtils.invokeMethod(service, "recoverOrdinaryDeliveries", NOW.plusMinutes(6));
        ReflectionTestUtils.invokeMethod(service, "recoverOrdinaryDeliveries", NOW.plusMinutes(12));
        assertThat(state.getSentCount()).isEqualTo(1);
        assertThat(state.getDeliveryEnvelope()).isNull();
        verify(sender, times(1)).recordedOutcome(prepared.operationId());
        verify(sender, never()).deliverWithOperationId(any(), any(), any(), any(), any(), any());
    }

    @Test void lateReceiptForClosedTaskIsRecordedOnceWithoutBusinessEffectsOrResend() {
        state.setScenario(ClientMessageScenario.REVIEW_RECOVERY_NOTICE);
        var prepared = ReflectionTestUtils.<ScheduledClientMessageService.PreparedScheduledDelivery>invokeMethod(service,
                "persistScheduledDelivery", state, company, manager, "recovered", null, null,
                "REVIEW_RECOVERY_NOTICE", 52L, null, null, NOW);
        state.setStatus(ScheduledMessageStateStatus.DONE);
        state.setLastErrorCode("canceled_by_user");
        String envelope = state.getDeliveryEnvelope();
        when(states.findRecoverablePreparedIds(any(), any())).thenReturn(List.of(71L));
        when(sender.recordedOutcome(prepared.operationId())).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return ClientMessageSendResult.sent("WhatsApp", "confirmed-id");
        });
        ReflectionTestUtils.invokeMethod(service, "recoverOrdinaryDeliveries", NOW.plusMinutes(6));
        ReflectionTestUtils.invokeMethod(service, "recoverOrdinaryDeliveries", NOW.plusMinutes(12));
        assertThat(state.getDeliveryStatus()).isEqualTo("SENT");
        assertThat(state.getStatus()).isEqualTo(ScheduledMessageStateStatus.DONE);
        assertThat(state.getLastErrorCode()).isEqualTo("canceled_by_user");
        assertThat(state.getNextAttemptAt()).isNull();
        assertThat(state.getDeliveryEnvelope()).isEqualTo(envelope);
        assertThat(state.getSentCount()).isEqualTo(1);
        verify(sender, times(1)).recordedOutcome(prepared.operationId());
        verifyNoMoreInteractions(sender);
        verifyNoInteractions(dependency(service, "reviewRecoveryTaskService", ReviewRecoveryTaskService.class));
        verifyNoInteractions(dependency(service, "whatsAppAuthAlertService", com.hunt.otziv.whatsapp.service.WhatsAppAuthAlertService.class));
    }

    @Test void closedTaskWithNoReceiptOrProvenUnsentNeverReopens() {
        var prepared = prepare("message", null);
        state.setStatus(ScheduledMessageStateStatus.DONE);
        state.setLastErrorCode("canceled_by_user");
        when(states.findRecoverablePreparedIds(any(), any())).thenReturn(List.of(71L));
        when(sender.recordedOutcome(prepared.operationId())).thenReturn(ClientMessageOperationFence.unknown(),
                ClientMessageSendResult.failed("gateway_not_ready", "before dispatch"));
        ReflectionTestUtils.invokeMethod(service, "recoverOrdinaryDeliveries", NOW.plusMinutes(6));
        ReflectionTestUtils.invokeMethod(service, "recoverOrdinaryDeliveries", NOW.plusMinutes(12));
        assertThat(state.getDeliveryStatus()).isEqualTo("UNKNOWN");
        assertThat(state.getStatus()).isEqualTo(ScheduledMessageStateStatus.DONE);
        assertThat(state.getLastErrorCode()).isEqualTo("canceled_by_user");
        assertThat(state.getSentCount()).isZero();
        assertThat(state.getNextAttemptAt()).isNull();
        verify(sender, times(2)).recordedOutcome(prepared.operationId());
        verifyNoMoreInteractions(sender);
    }

    @Test void aNewerPreparationAfterCandidateSelectionCannotBeReclassifiedUnknown() {
        prepare("message", null);
        when(states.findRecoverablePreparedIds(any(), any())).thenReturn(List.of(71L));
        state.setDeliveryPreparedAt(NOW.plusMinutes(6));
        ReflectionTestUtils.invokeMethod(service, "recoverOrdinaryDeliveries", NOW.plusMinutes(6));
        assertThat(state.getDeliveryStatus()).isEqualTo("PREPARED");
        verifyNoInteractions(sender);
    }

    @Test void corruptSnapshotRetainsUnknownBackoffAndNeverCallsProvider() {
        prepare("message", null);
        state.setDeliveryEnvelope("{broken JSON");
        when(states.findRecoverablePreparedIds(any(), any())).thenReturn(List.of(71L));
        ReflectionTestUtils.invokeMethod(service, "recoverOrdinaryDeliveries", NOW.plusMinutes(6));
        assertThat(state.getDeliveryStatus()).isEqualTo("UNKNOWN");
        assertThat(state.getDeliveryRecoveryCheckedAt()).isEqualTo(NOW.plusMinutes(6));
        assertThat(state.getNextAttemptAt()).isNull();
        verifyNoInteractions(sender);
    }

    @Test void lateProviderResultCannotFinalizeAnotherOccurrence() {
        var prepared = prepare("message", null);
        state.setDeliveryToken("replacement-token");
        Boolean applied = ReflectionTestUtils.invokeMethod(service, "finalizeScheduledDelivery", prepared,
                ClientMessageSendResult.sent("Telegram", "42"), NOW, 1L);
        assertThat(applied).isFalse();
        assertThat(state.getSentCount()).isZero();
        verify(states, never()).lockDispatchBudget();
    }

    @Test void providerRunsOutsideTransactionWhileFinalizeRunsInFreshTransaction() {
        var prepared = prepare("message", null);
        when(sender.deliverWithOperationId(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(state.getDeliveryStatus()).isEqualTo("PREPARED");
            return ClientMessageSendResult.sent("Telegram", "42");
        });
        when(states.lockDispatchBudget()).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return 1;
        });
        service.dispatchScheduledDelivery(prepared, NOW);
        assertThat(state.getSentCount()).isEqualTo(1);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> service.dispatchScheduledDelivery(prepared, NOW)).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verify(sender, times(1)).deliverWithOperationId(any(), any(), any(), any(), any(), any());
    }

    @Test void recoveryNoticeSuccessKeepsItsBusinessSideEffect() {
        state.setScenario(ClientMessageScenario.REVIEW_RECOVERY_NOTICE);
        state.setTargetKey("review-recovery:batch:52");
        var prepared = ReflectionTestUtils.<ScheduledClientMessageService.PreparedScheduledDelivery>invokeMethod(service,
                "persistScheduledDelivery", state, company, manager, "recovered", null, null,
                "REVIEW_RECOVERY_NOTICE", 52L, null, null, NOW);
        when(sender.deliverWithOperationId(any(), any(), any(), any(), any(), any())).thenReturn(ClientMessageSendResult.sent("Telegram", "42"));
        service.dispatchScheduledDelivery(prepared, NOW);
        verify(dependency(service, "reviewRecoveryTaskService", ReviewRecoveryTaskService.class)).markClientNotifiedAutomatically(52L);
        assertThat(state.getStatus()).isEqualTo(ScheduledMessageStateStatus.DONE);
    }

    @Test void actionRetriesUseTheirFrozenOccurrenceAndFinalizeOrderActionOnlyAfterSuccess() {
        state.setOrderId(5L);
        state.setScenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY);
        var order = Order.builder().id(5L).clientMessageGeneration(4).status(OrderStatus.builder().title("Опубликовано").build()).build();
        when(dependency(service, "orderRepository", OrderRepository.class).findByIdForMutation(5L)).thenReturn(Optional.of(order));
        var actions = dependency(service, "orderStatusNotificationService", OrderStatusNotificationService.class);
        var action = new OrderStatusNotificationService.PreparedAction(5L, "Опубликовано", "Выставлен счет", 4,
                "action:Опубликовано:Выставлен счет", "existing-order-occurrence", null, "client", "group", "1200 руб.",
                "+79991112233", null, null, List.of());
        var prepared = ReflectionTestUtils.<ScheduledClientMessageService.PreparedScheduledDelivery>invokeMethod(service,
                "persistScheduledDelivery", state, company, manager, action.message(), action.copy(), null,
                null, null, order, action, NOW);
        when(actions.dispatchPreparedAction(action)).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return ClientMessageSendResult.sent("Telegram", "42");
        });
        service.dispatchScheduledDelivery(prepared, NOW);
        verify(actions).applyPreparedAction(order, action);
        verify(actions).notifyPreparedActionOutcome(eq(action), any());
        assertThat(prepared.operationId()).isEqualTo("existing-order-occurrence");
        assertThat(state.getStatus()).isEqualTo(ScheduledMessageStateStatus.DONE);
        verifyNoInteractions(sender);
    }
}
