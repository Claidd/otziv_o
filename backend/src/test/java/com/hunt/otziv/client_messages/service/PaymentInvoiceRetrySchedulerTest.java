package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PaymentInvoiceRetrySchedulerTest {

    @Mock
    private ScheduledClientMessageStateRepository stateRepository;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private ClientMessageSlotPlanner slotPlanner;

    @Test
    void afterCommitBadReviewAutoBanCancellationAlwaysOpensNewTransaction() throws Exception {
        Transactional transactional = PaymentInvoiceRetryScheduler.class
                .getMethod("cancelBadReviewAutoBanInNewTransaction", Long.class, String.class)
                .getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }

    @Test
    void afterCommitBadReviewAutoBanCancellationClosesActiveStateByOrderId() {
        PaymentInvoiceRetryScheduler scheduler = new PaymentInvoiceRetryScheduler(
                stateRepository,
                appSettingService,
                slotPlanner
        );
        ScheduledClientMessageState activeAutoBan = state(
                ClientMessageScenario.BAD_REVIEW_AUTO_BAN,
                ScheduledMessageStateStatus.ACTIVE
        );
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.BAD_REVIEW_AUTO_BAN,
                "bad-review-auto-ban:order:25047"
        )).thenReturn(Optional.of(activeAutoBan));

        scheduler.cancelBadReviewAutoBanInNewTransaction(25047L, "Оплата подтверждена");

        assertEquals(ScheduledMessageStateStatus.DONE, activeAutoBan.getStatus());
        assertNull(activeAutoBan.getNextAttemptAt());
        assertNull(activeAutoBan.getLockedUntil());
        assertEquals("bad_review_auto_ban_canceled", activeAutoBan.getLastErrorCode());
        assertEquals("Оплата подтверждена", activeAutoBan.getLastErrorMessage());
        verify(stateRepository).save(activeAutoBan);
    }

    @Test
    void finalManualPaymentClosesActiveAndPausedPaymentAutomation() {
        PaymentInvoiceRetryScheduler scheduler = new PaymentInvoiceRetryScheduler(
                stateRepository,
                appSettingService,
                slotPlanner
        );
        ScheduledClientMessageState activeReminder = state(
                ClientMessageScenario.PAYMENT_REMINDER,
                ScheduledMessageStateStatus.ACTIVE
        );
        ScheduledClientMessageState pausedAutoBan = state(
                ClientMessageScenario.BAD_REVIEW_AUTO_BAN,
                ScheduledMessageStateStatus.PAUSED
        );
        ScheduledClientMessageState unrelated = state(
                ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY,
                ScheduledMessageStateStatus.ACTIVE
        );
        when(stateRepository.findByOrderIdInForUpdate(List.of(25047L)))
                .thenReturn(List.of(activeReminder, pausedAutoBan, unrelated));

        int changed = scheduler.cancelPaymentAutomation(25047L, "Оплата подтверждена");

        assertEquals(2, changed);
        assertEquals(ScheduledMessageStateStatus.DONE, activeReminder.getStatus());
        assertEquals(ScheduledMessageStateStatus.DONE, pausedAutoBan.getStatus());
        assertEquals(ScheduledMessageStateStatus.ACTIVE, unrelated.getStatus());
        assertNull(activeReminder.getNextAttemptAt());
        assertNull(pausedAutoBan.getLockedUntil());
        assertEquals("manual_card_payment_confirmed", activeReminder.getLastErrorCode());
        assertEquals("Оплата подтверждена", pausedAutoBan.getLastErrorMessage());
        verify(stateRepository).saveAll(List.of(activeReminder, pausedAutoBan));
    }

    @Test
    void preparedBadReviewInvoiceCannotBeCanceledWhileExternalSendIsInFlight() {
        PaymentInvoiceRetryScheduler scheduler = scheduler();
        Order order = order();
        ScheduledClientMessageState prepared = state(
                ClientMessageScenario.BAD_REVIEW_INVOICE,
                ScheduledMessageStateStatus.ACTIVE
        );
        prepared.setDeliveryStatus(ClientMessageStateSafety.DELIVERY_PREPARED);
        prepared.setLastErrorCode("delivery_prepared");
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.BAD_REVIEW_INVOICE,
                "bad-review-invoice:order:25047"
        )).thenReturn(Optional.of(prepared));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> scheduler.cancelBadReviewInvoiceRetry(order, "Отмена задачи")
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(ScheduledMessageStateStatus.ACTIVE, prepared.getStatus());
        assertEquals(ClientMessageStateSafety.DELIVERY_PREPARED, prepared.getDeliveryStatus());
        verify(stateRepository, never()).save(any());
    }

    @Test
    void manualPaymentCannotCrossPreparedInvoiceSend() {
        PaymentInvoiceRetryScheduler scheduler = scheduler();
        ScheduledClientMessageState prepared = state(
                ClientMessageScenario.BAD_REVIEW_INVOICE,
                ScheduledMessageStateStatus.ACTIVE
        );
        prepared.setDeliveryStatus(ClientMessageStateSafety.DELIVERY_PREPARED);
        prepared.setLastErrorCode("delivery_prepared");
        when(stateRepository.findByOrderIdInForUpdate(List.of(25047L)))
                .thenReturn(List.of(prepared));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> scheduler.assertPaymentAutomationMutable(25047L)
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(ScheduledMessageStateStatus.ACTIVE, prepared.getStatus());
        verify(stateRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE, PREPARED", "DONE, PREPARED", "ACTIVE, UNKNOWN", "DONE, UNKNOWN",
            "ACTIVE, RETRYABLE", "PAUSED, RETRYABLE", "ACTIVE, CLAIMED"})
    void routeReplacementCannotLeaveOldPaymentInstructionsPending(
            ScheduledMessageStateStatus status, String deliveryStatus) {
        PaymentInvoiceRetryScheduler scheduler = scheduler();
        ScheduledClientMessageState invoice = state(ClientMessageScenario.PAYMENT_INVOICE_RETRY, status);
        invoice.setDeliveryStatus(deliveryStatus);
        invoice.setDeliveryEnvelope("original-invoice-envelope");
        invoice.setDeliveryToken("original-operation-token");
        invoice.setDeliveryMessage("original-payment-instruction");
        invoice.setLastErrorCode("CLAIMED".equals(deliveryStatus)
                ? ClientMessageStateSafety.TRANSACTION_IN_PROGRESS : null);
        when(stateRepository.findByOrderIdInForUpdate(List.of(25047L))).thenReturn(List.of(invoice));
        when(stateRepository.findByOrderIdIn(List.of(25047L))).thenReturn(List.of(invoice));

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> scheduler.assertPaymentRouteReplaceable(25047L));

        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(Optional.of(conflict.getReason()), scheduler.paymentRouteReplacementBlockReason(25047L));
        assertEquals(status, invoice.getStatus());
        assertEquals(deliveryStatus, invoice.getDeliveryStatus());
        assertEquals("original-invoice-envelope", invoice.getDeliveryEnvelope());
        assertEquals("original-operation-token", invoice.getDeliveryToken());
        verify(stateRepository, never()).save(any());
        verify(stateRepository, never()).saveAll(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unprepared", "completed", "completed-unsent", "completed-sent", "active-sent", "unrelated"})
    void routeReplacementAllowsTerminalHistoryAndMessagesWithoutFrozenPaymentDetails(String condition) {
        PaymentInvoiceRetryScheduler scheduler = scheduler();
        ScheduledClientMessageState invoice = state(ClientMessageScenario.PAYMENT_INVOICE_RETRY,
                ScheduledMessageStateStatus.DONE);
        invoice.setDeliveryEnvelope("historical-invoice-envelope");
        invoice.setLastErrorCode(null);
        switch (condition) {
            case "unprepared" -> {
                invoice.setStatus(ScheduledMessageStateStatus.ACTIVE);
                invoice.setDeliveryEnvelope(null);
            }
            case "completed" -> invoice.setDeliveryStatus(null);
            case "completed-unsent" -> invoice.setDeliveryStatus("RETRYABLE");
            case "completed-sent" -> {
                invoice.setDeliveryStatus("SENT");
                invoice.setLastErrorCode(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN);
            }
            case "active-sent" -> {
                invoice.setStatus(ScheduledMessageStateStatus.ACTIVE);
                invoice.setDeliveryStatus("SENT");
            }
            case "unrelated" -> {
                invoice.setScenario(ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY);
                invoice.setDeliveryStatus("PREPARED");
            }
            default -> throw new IllegalArgumentException(condition);
        }
        when(stateRepository.findByOrderIdInForUpdate(List.of(25047L))).thenReturn(List.of(invoice));
        when(stateRepository.findByOrderIdIn(List.of(25047L))).thenReturn(List.of(invoice));

        assertDoesNotThrow(() -> scheduler.assertPaymentRouteReplaceable(25047L));
        assertEquals(Optional.empty(), scheduler.paymentRouteReplacementBlockReason(25047L));
        verify(stateRepository, never()).save(any());
        verify(stateRepository, never()).saveAll(any());
    }

    @Test
    void secondBadReviewCompletionCannotRearmPreparedInvoice() {
        PaymentInvoiceRetryScheduler scheduler = scheduler();
        Order order = order();
        ScheduledClientMessageState prepared = state(
                ClientMessageScenario.BAD_REVIEW_INVOICE,
                ScheduledMessageStateStatus.ACTIVE
        );
        prepared.setDeliveryStatus(ClientMessageStateSafety.DELIVERY_PREPARED);
        prepared.setLastErrorCode("delivery_prepared");
        stubBadReviewSchedule(prepared);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> scheduler.scheduleBadReviewInvoiceRetry(order)
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(ClientMessageStateSafety.DELIVERY_PREPARED, prepared.getDeliveryStatus());
        verify(stateRepository, never()).save(any());
    }

    @Test
    void uncertainBadReviewDeliveryCannotBeAutomaticallyRearmed() {
        PaymentInvoiceRetryScheduler scheduler = scheduler();
        ScheduledClientMessageState uncertain = state(
                ClientMessageScenario.BAD_REVIEW_INVOICE,
                ScheduledMessageStateStatus.ACTIVE
        );
        uncertain.setDeliveryStatus(ClientMessageStateSafety.DELIVERY_OUTCOME_UNKNOWN);
        uncertain.setLastErrorCode(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN);
        stubBadReviewSchedule(uncertain);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> scheduler.scheduleBadReviewInvoiceRetry(order())
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals(ClientMessageStateSafety.DELIVERY_OUTCOME_UNKNOWN, uncertain.getDeliveryStatus());
        verify(stateRepository, never()).save(any());
    }

    @Test
    void completedDeliveryCanBeRearmedForNextBadReviewAndClearsOldFence() {
        PaymentInvoiceRetryScheduler scheduler = scheduler();
        Order order = order();
        ScheduledClientMessageState sent = state(
                ClientMessageScenario.BAD_REVIEW_INVOICE,
                ScheduledMessageStateStatus.DONE
        );
        sent.setDeliveryStatus("SENT");
        sent.setDeliveryToken("old-token");
        sent.setDeliveryMessage("old-message");
        sent.setDeliveryEnvelope("old-invoice-and-link");
        sent.setDeliveryChannel("WhatsApp");
        sent.setDeliveryTaskId(40L);
        sent.setDeliveryPreparedAt(LocalDateTime.now());
        sent.setDeliveryRecoveryCheckedAt(LocalDateTime.now());
        stubBadReviewSchedule(sent);

        scheduler.scheduleBadReviewInvoiceRetry(order);

        assertEquals(ScheduledMessageStateStatus.ACTIVE, sent.getStatus());
        assertNull(sent.getDeliveryStatus());
        assertNull(sent.getDeliveryToken());
        assertNull(sent.getDeliveryMessage());
        assertNull(sent.getDeliveryEnvelope());
        assertNull(sent.getDeliveryChannel());
        assertNull(sent.getDeliveryTaskId());
        assertNull(sent.getDeliveryPreparedAt());
        assertNull(sent.getDeliveryRecoveryCheckedAt());
        verify(stateRepository).save(sent);
    }

    @Test
    void disabledAutoBanScenarioDoesNotPreventDurableStateCreation() {
        PaymentInvoiceRetryScheduler scheduler = new PaymentInvoiceRetryScheduler(
                stateRepository,
                appSettingService,
                slotPlanner
        );
        Company company = new Company();
        company.setId(70L);
        Order order = new Order();
        order.setId(25047L);
        order.setCompany(company);
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.BAD_REVIEW_AUTO_BAN,
                "bad-review-auto-ban:order:25047"
        )).thenReturn(Optional.empty());
        when(appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_DELAY_DAYS,
                ScheduledClientMessageService.DEFAULT_BAD_REVIEW_AUTO_BAN_DELAY_DAYS
        )).thenReturn(2);
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_BUSINESS_WINDOWS,
                ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC
        )).thenReturn(ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        scheduler.scheduleBadReviewAutoBan(order);

        verify(stateRepository).save(org.mockito.ArgumentMatchers.argThat(state ->
                state.getScenario() == ClientMessageScenario.BAD_REVIEW_AUTO_BAN
                        && state.getStatus() == ScheduledMessageStateStatus.ACTIVE
                        && state.getNextAttemptAt() != null
        ));
        verify(appSettingService, never()).getBoolean(
                eq(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_ENABLED), anyBoolean());
    }

    @Test
    void manualSettlementClosesStaleReminderWithoutClaimingDeliveryOrLosingEvidence() throws Exception {
        ScheduledClientMessageState reminder = uncertainReminder();
        LocalDateTime attemptedAt = reminder.getLastAttemptAt();
        ScheduledClientMessageState overdue = state(ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION,
                ScheduledMessageStateStatus.ACTIVE);
        when(stateRepository.findByOrderIdInForUpdate(List.of(25047L))).thenReturn(List.of(reminder, overdue));

        assertEquals(2, scheduler().closePaymentAutomationForManualSettlement(25047L, "Оплата подтверждена"));

        assertEquals(ScheduledMessageStateStatus.DONE, reminder.getStatus());
        assertEquals(ScheduledMessageStateStatus.DONE, overdue.getStatus());
        assertNull(reminder.getNextAttemptAt());
        assertNull(reminder.getLockedUntil());
        assertEquals("UNKNOWN", reminder.getDeliveryStatus());
        assertEquals("saved-token", reminder.getDeliveryToken());
        assertEquals("saved-envelope", reminder.getDeliveryEnvelope());
        assertEquals("saved-message", reminder.getDeliveryMessage());
        assertEquals("state_transaction_outcome_uncertain", reminder.getLastErrorCode());
        assertEquals("original error", reminder.getLastErrorMessage());
        assertEquals(attemptedAt, reminder.getLastAttemptAt());
        assertEquals(0, reminder.getSentCount());
        assertNull(reminder.getLastSuccessAt());
        assertEquals(0, scheduler().closePaymentAutomationForManualSettlement(25047L, "Повтор запроса"));
        assertThrows(ResponseStatusException.class, () -> scheduler().assertPaymentAutomationMutable(25047L));
        assertEquals(Propagation.MANDATORY, PaymentInvoiceRetryScheduler.class
                .getMethod("closePaymentAutomationForManualSettlement", Long.class, String.class)
                .getAnnotation(Transactional.class).propagation());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prepared", "recent", "lease", "missing-envelope", "missing-token",
            "invoice", "bad-review-invoice", "legacy", "transaction"})
    void settlementRetainsOtherDeliveryFencesWithoutPartiallyClosingStates(String condition) {
        ScheduledClientMessageState blocked = uncertainReminder();
        switch (condition) {
            case "prepared" -> blocked.setDeliveryStatus("PREPARED");
            case "recent" -> blocked.setDeliveryPreparedAt(LocalDateTime.now());
            case "lease" -> blocked.setLockedUntil(LocalDateTime.now().plusMinutes(10));
            case "missing-envelope" -> blocked.setDeliveryEnvelope(null);
            case "missing-token" -> blocked.setDeliveryToken(null);
            case "invoice" -> blocked.setScenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY);
            case "bad-review-invoice" -> blocked.setScenario(ClientMessageScenario.BAD_REVIEW_INVOICE);
            case "legacy" -> blocked.setLastErrorCode(ClientMessageStateSafety.LEGACY_PREPARATION_UNVERIFIED);
            case "transaction" -> blocked.setLastErrorCode(ClientMessageStateSafety.TRANSACTION_IN_PROGRESS);
            default -> throw new IllegalArgumentException(condition);
        }
        ScheduledClientMessageState allowed = uncertainReminder();
        when(stateRepository.findByOrderIdInForUpdate(List.of(25047L))).thenReturn(List.of(allowed, blocked));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> scheduler().closePaymentAutomationForManualSettlement(25047L, "Оплата подтверждена"));

        assertEquals(409, failure.getStatusCode().value());
        assertEquals(ScheduledMessageStateStatus.ACTIVE, allowed.getStatus());
        assertEquals(ScheduledMessageStateStatus.ACTIVE, blocked.getStatus());
        verify(stateRepository, never()).saveAll(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"state_transaction_outcome_uncertain", " "})
    void manualSettlementIgnoresCompletedInvoiceWithoutChangingItsUncertainDelivery(String errorCode) {
        ScheduledClientMessageState invoice = completedUncertainInvoice();
        invoice.setLastErrorCode(errorCode);
        invoice.setLastErrorMessage(null);
        invoice.setDeliveryChannel("WhatsApp");
        invoice.setDeliveryTaskId(40L);
        invoice.setDeliveryRecoveryCheckedAt(LocalDateTime.now().minusMinutes(10));
        LocalDateTime preparedAt = invoice.getDeliveryPreparedAt();
        LocalDateTime attemptedAt = invoice.getLastAttemptAt();
        LocalDateTime checkedAt = invoice.getDeliveryRecoveryCheckedAt();
        ScheduledClientMessageState reminder = state(ClientMessageScenario.PAYMENT_REMINDER,
                ScheduledMessageStateStatus.ACTIVE);
        ScheduledClientMessageState overdue = state(ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION,
                ScheduledMessageStateStatus.ACTIVE);
        when(stateRepository.findByOrderIdInForUpdate(List.of(25047L)))
                .thenReturn(List.of(invoice, reminder, overdue));

        assertEquals(2, scheduler().closePaymentAutomationForManualSettlement(25047L, "Оплата подтверждена"));

        assertEquals(ScheduledMessageStateStatus.DONE, reminder.getStatus());
        assertEquals(ScheduledMessageStateStatus.DONE, overdue.getStatus());
        assertEquals(ScheduledMessageStateStatus.DONE, invoice.getStatus());
        assertEquals("UNKNOWN", invoice.getDeliveryStatus());
        assertEquals(errorCode, invoice.getLastErrorCode());
        assertNull(invoice.getLastErrorMessage());
        assertEquals("saved-token", invoice.getDeliveryToken());
        assertEquals("saved-envelope", invoice.getDeliveryEnvelope());
        assertEquals("saved-message", invoice.getDeliveryMessage());
        assertEquals("WhatsApp", invoice.getDeliveryChannel());
        assertEquals(40L, invoice.getDeliveryTaskId());
        assertEquals(preparedAt, invoice.getDeliveryPreparedAt());
        assertEquals(attemptedAt, invoice.getLastAttemptAt());
        assertEquals(checkedAt, invoice.getDeliveryRecoveryCheckedAt());
        assertEquals(3, invoice.getConsecutiveFailures());
        assertEquals(0, invoice.getSentCount());
        assertNull(invoice.getLastSuccessAt());
        assertNull(invoice.getNextAttemptAt());
        assertNull(invoice.getLockedUntil());
        verify(stateRepository).saveAll(List.of(reminder, overdue));
        assertEquals(0, scheduler().closePaymentAutomationForManualSettlement(25047L, "Повтор запроса"));
        assertThrows(ResponseStatusException.class, () -> scheduler().assertPaymentAutomationMutable(25047L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "paused", "disabled", "prepared", "claimed", "recent", "lease",
            "next-attempt", "missing-prepared", "missing-envelope", "blank-envelope", "missing-token", "blank-token",
            "legacy", "transaction", "other-error", "bad-review-invoice"})
    void completedInvoiceExceptionRetainsUnsafeFencesWithoutPartiallyClosingCurrentTasks(String condition) {
        ScheduledClientMessageState blocked = completedUncertainInvoice();
        switch (condition) {
            case "active" -> blocked.setStatus(ScheduledMessageStateStatus.ACTIVE);
            case "paused" -> blocked.setStatus(ScheduledMessageStateStatus.PAUSED);
            case "disabled" -> blocked.setStatus(ScheduledMessageStateStatus.DISABLED);
            case "prepared" -> blocked.setDeliveryStatus("PREPARED");
            case "claimed" -> {
                blocked.setDeliveryStatus("CLAIMED");
                blocked.setLastErrorCode(ClientMessageStateSafety.TRANSACTION_IN_PROGRESS);
            }
            case "recent" -> blocked.setDeliveryPreparedAt(LocalDateTime.now());
            case "lease" -> blocked.setLockedUntil(LocalDateTime.now().plusMinutes(10));
            case "next-attempt" -> blocked.setNextAttemptAt(LocalDateTime.now().plusHours(1));
            case "missing-prepared" -> blocked.setDeliveryPreparedAt(null);
            case "missing-envelope" -> blocked.setDeliveryEnvelope(null);
            case "blank-envelope" -> blocked.setDeliveryEnvelope(" ");
            case "missing-token" -> blocked.setDeliveryToken(null);
            case "blank-token" -> blocked.setDeliveryToken(" ");
            case "legacy" -> blocked.setLastErrorCode(ClientMessageStateSafety.LEGACY_PREPARATION_UNVERIFIED);
            case "transaction" -> blocked.setLastErrorCode(ClientMessageStateSafety.TRANSACTION_IN_PROGRESS);
            case "other-error" -> blocked.setLastErrorCode("unrecognized_error");
            case "bad-review-invoice" -> blocked.setScenario(ClientMessageScenario.BAD_REVIEW_INVOICE);
            default -> throw new IllegalArgumentException(condition);
        }
        ScheduledClientMessageState reminder = uncertainReminder();
        ScheduledMessageStateStatus originalStatus = blocked.getStatus();
        String originalDelivery = blocked.getDeliveryStatus();
        when(stateRepository.findByOrderIdInForUpdate(List.of(25047L))).thenReturn(List.of(reminder, blocked));

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> scheduler().closePaymentAutomationForManualSettlement(25047L, "Оплата подтверждена"));

        assertEquals(409, failure.getStatusCode().value());
        assertEquals(ScheduledMessageStateStatus.ACTIVE, reminder.getStatus());
        assertEquals(originalStatus, blocked.getStatus());
        assertEquals(originalDelivery, blocked.getDeliveryStatus());
        verify(stateRepository, never()).saveAll(any());
    }

    private ScheduledClientMessageState completedUncertainInvoice() {
        ScheduledClientMessageState invoice = uncertainReminder();
        invoice.setScenario(ClientMessageScenario.PAYMENT_INVOICE_RETRY);
        invoice.setStatus(ScheduledMessageStateStatus.DONE);
        invoice.setLastErrorCode(null);
        invoice.setLastErrorMessage(null);
        invoice.setNextAttemptAt(null);
        return invoice;
    }

    private ScheduledClientMessageState uncertainReminder() {
        ScheduledClientMessageState reminder = state(ClientMessageScenario.PAYMENT_REMINDER,
                ScheduledMessageStateStatus.ACTIVE);
        reminder.setDeliveryStatus("UNKNOWN");
        reminder.setLastErrorCode(ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN);
        reminder.setLastErrorMessage("original error");
        reminder.setDeliveryToken("saved-token");
        reminder.setDeliveryEnvelope("saved-envelope");
        reminder.setDeliveryMessage("saved-message");
        reminder.setDeliveryPreparedAt(LocalDateTime.now().minusDays(5));
        reminder.setLastAttemptAt(LocalDateTime.now().minusDays(5));
        reminder.setLockedUntil(null);
        return reminder;
    }

    private PaymentInvoiceRetryScheduler scheduler() {
        return new PaymentInvoiceRetryScheduler(stateRepository, appSettingService, slotPlanner);
    }

    private Order order() {
        Company company = new Company();
        company.setId(70L);
        Order order = new Order();
        order.setId(25047L);
        order.setCompany(company);
        return order;
    }

    private void stubBadReviewSchedule(ScheduledClientMessageState state) {
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.BAD_REVIEW_INVOICE,
                "bad-review-invoice:order:25047"
        )).thenReturn(Optional.of(state));
        when(appSettingService.getString(
                AppSettingService.CLIENT_MESSAGES_BUSINESS_WINDOWS,
                ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC
        )).thenReturn(ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ScheduledClientMessageState state(
            ClientMessageScenario scenario,
            ScheduledMessageStateStatus status
    ) {
        return ScheduledClientMessageState.builder()
                .scenario(scenario)
                .orderId(25047L)
                .targetKey(scenario.name() + ":25047")
                .status(status)
                .nextAttemptAt(LocalDateTime.now().plusHours(1))
                .lockedUntil(LocalDateTime.now().plusMinutes(5))
                .consecutiveFailures(3)
                .build();
    }
}
