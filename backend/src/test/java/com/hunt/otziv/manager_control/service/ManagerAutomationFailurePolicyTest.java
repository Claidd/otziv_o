package com.hunt.otziv.manager_control.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ManagerAutomationFailurePolicyTest {

    private final ManagerAutomationFailurePolicy policy = new ManagerAutomationFailurePolicy();
    private final LocalDateTime now = LocalDateTime.of(2026, 7, 26, 12, 0);

    @Test void activeWorkFailuresAreImmediateButIntentionalPausesAndLeasesAreNot() {
        assertTrue(policy.isImmediateActiveOrderFailure(state("remote_service_unavailable",1,now),now));
        assertTrue(policy.isImmediateActiveOrderFailure(state("operation_unknown",1,now),now));
        for(String code:ListOfWaits()) assertFalse(policy.isImmediateActiveOrderFailure(state(code,3,now.minusDays(2)),now),code);
        var inFlight=state("state_transaction_in_progress",1,now);inFlight.setLockedUntil(now.plusMinutes(5));
        assertFalse(policy.isImmediateActiveOrderFailure(inFlight,now));
    }
    private String[] ListOfWaits() { return new String[]{"rate_limited","daily_limit","outside_messaging_window","manual_contact_reserved",
            "review_recovery_active","dry_run",com.hunt.otziv.client_messages.api.ClientContactHealth.HELD_CODE}; }

    @Test
    void exposesPaymentInstructionFailureImmediately() {
        ScheduledClientMessageState state = state("payment_instruction_failed", 1, now.minusMinutes(2));

        assertTrue(policy.isActionable(state, now, 3, 60));
    }

    @Test
    void exposesRepeatedClientChatFailure() {
        ScheduledClientMessageState state = state("client_chat_send_failed", 3, now.minusMinutes(2));

        assertTrue(policy.isActionable(state, now, 3, 60));
    }

    @Test
    void exposesOldNonBenignFailureEvenBeforeThreshold() {
        ScheduledClientMessageState state = state("remote_service_unavailable", 1, now.minusMinutes(61));

        assertTrue(policy.isActionable(state, now, 3, 60));
    }

    @Test
    void ignoresExpectedRecoveryHoldAndTransientRetry() {
        ScheduledClientMessageState recovery = state("review_recovery_active", 20, now.minusDays(2));
        ScheduledClientMessageState transientRetry = state("rate_limited", 1, now.minusDays(2));

        assertFalse(policy.isActionable(recovery, now, 3, 60));
        assertFalse(policy.isActionable(transientRetry, now, 3, 60));
    }

    @Test
    void ignoresReminderHeldBehindTheManagersOwnMessageEvenAfterAnEarlierFailure() {
        // A failed attempt left one failure; the manager then messaged the client by hand for this cycle.
        ScheduledClientMessageState state = state("manual_contact_reserved", 1, now.minusHours(6));
        state.setDeliveryStatus("RETRYABLE");
        state.setNextAttemptAt(now.plusMinutes(5));

        assertFalse(policy.isActionable(state, now, 1, 60));
        assertFalse(policy.requiresDeliveryReceipt(state));
    }

    @Test
    void scenarioSwitchedOffInTheSettingsIsNeverAFailureCard() {
        // processState postpones such a task by a day without counting a failure: after an hour it must not turn into
        // «задача завершилась ошибкой, нажмите Починить».
        for (String code : new String[]{"automation_paused", "bad_review_invoice_disabled", "bad_review_auto_ban_disabled"}) {
            ScheduledClientMessageState state = state(code, 0, now.minusHours(3));
            state.setNextAttemptAt(now.plusHours(21));
            assertFalse(policy.isActionable(state, now, 3, 60), code);
            assertFalse(policy.isImmediateActiveOrderFailure(state, now), code);
        }
    }

    @Test
    void anUnconfirmedSendIsShownOnlyAfterTheAutomaticResendHadItsChance() {
        ScheduledClientMessageState fresh = state("state_transaction_outcome_uncertain", 1, now.minusHours(2));
        fresh.setDeliveryStatus("UNKNOWN");
        fresh.setDeliveryPreparedAt(now.minusHours(2));
        // The system re-sends it once after 24 h by itself: no card yet.
        assertFalse(policy.isActionable(fresh, now, 3, 60));
        assertFalse(policy.isImmediateActiveOrderFailure(fresh, now));

        ScheduledClientMessageState overdue = state("state_transaction_outcome_uncertain", 1, now.minusHours(26));
        overdue.setDeliveryStatus("UNKNOWN");
        overdue.setDeliveryPreparedAt(now.minusHours(26));
        assertTrue(policy.isActionable(overdue, now, 3, 60));

        ScheduledClientMessageState afterResend = state("state_transaction_outcome_uncertain", 1, now.minusHours(1));
        afterResend.setDeliveryStatus("UNKNOWN");
        afterResend.setDeliveryPreparedAt(now.minusHours(1));
        afterResend.setUncertainAutoResendAt(now.minusHours(1));
        assertTrue(policy.isActionable(afterResend, now, 3, 60));

        ScheduledClientMessageState archiveOffer = state("state_transaction_outcome_uncertain", 1, now.minusHours(1));
        archiveOffer.setScenario(ClientMessageScenario.ARCHIVE_REORDER_OFFER);
        archiveOffer.setDeliveryStatus("UNKNOWN");
        archiveOffer.setDeliveryPreparedAt(now.minusHours(1));
        assertTrue(policy.isActionable(archiveOffer, now, 3, 60));
    }

    @Test
    void anArchiveCheckThatCannotReadTheChatIsShownAtOnce() {
        assertTrue(policy.isActionable(state("archive_check_undecided", 0, now.minusMinutes(1)), now, 3, 60));
    }

    @Test
    void ignoresRepeatedRateLimitWhileNextRetryIsScheduled() {
        ScheduledClientMessageState state = state("rate_limited", 24, now.minusHours(1));
        state.setScenario(ClientMessageScenario.ARCHIVE_REORDER_OFFER);
        state.setTargetType(ClientMessageTargetType.ARCHIVE_COMPANY);
        state.setOrderId(null);
        state.setCompanyId(397L);
        state.setNextAttemptAt(now.plusHours(10));

        assertFalse(policy.isActionable(state, now, 3, 60));
    }

    @Test
    void exposesUncertainTransactionOutcomeImmediately() {
        ScheduledClientMessageState state = state(
                "state_transaction_outcome_uncertain",
                1,
                now
        );

        assertTrue(policy.isActionable(state, now, 3, 60));
    }

    @Test void keepsManualHandoffVisibleImmediately() {
        ScheduledClientMessageState state = state("manual_delivery_handoff_pending", 0, now);
        state.setDeliveryStatus("UNKNOWN");
        assertTrue(policy.isActionable(state, now, 3, 60));
    }

    @Test
    void exposesInProgressTransactionOnlyAfterItsClaimExpires() {
        ScheduledClientMessageState state = state("state_transaction_in_progress", 0, null);
        state.setLockedUntil(now.plusMinutes(1));

        assertFalse(policy.isActionable(state, now, 3, 60));

        state.setLockedUntil(now.minusSeconds(1));
        assertTrue(policy.isActionable(state, now, 3, 60));
    }

    @Test
    void explainsLegacyPreparationWithoutPromisingAResend() {
        var state = state("state_transaction_outcome_uncertain", 1, now);
        state.setLastErrorMessage("Транзакция обработки откатилась. Причина: legacy_operation_unverified");
        assertTrue(policy.isLegacyPreparationFailure(state));
        assertFalse(policy.requiresDeliveryReceipt(state));
    }

    @Test
    void actualUnknownDeliveryAlwaysRequiresReceiptEvenWithLegacyErrorText() {
        var state = state("legacy_operation_unverified", 1, now);
        state.setDeliveryStatus("UNKNOWN");
        assertFalse(policy.isLegacyPreparationFailure(state));
        assertTrue(policy.requiresDeliveryReceipt(state));
    }

    @Test void unknownForActiveWorkIsVisibleEvenWithoutAnErrorCode() {
        var state = state(null, 0, now);
        state.setDeliveryStatus("UNKNOWN");
        assertTrue(policy.isImmediateActiveOrderFailure(state, now));
        state.setDeliveryStatus("READY");
        assertFalse(policy.isImmediateActiveOrderFailure(state, now));
    }

    private ScheduledClientMessageState state(String code, int failures, LocalDateTime attemptedAt) {
        return ScheduledClientMessageState.builder()
                .id(1L)
                .scenario(ClientMessageScenario.PAYMENT_REMINDER)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey("order:1")
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode(code)
                .consecutiveFailures(failures)
                .lastAttemptAt(attemptedAt)
                .createdAt(now.minusDays(1))
                .updatedAt(attemptedAt)
                .build();
    }
}
