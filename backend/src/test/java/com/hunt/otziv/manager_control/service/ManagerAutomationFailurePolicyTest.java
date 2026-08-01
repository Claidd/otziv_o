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

    @Test
    void exposesInProgressTransactionOnlyAfterItsClaimExpires() {
        ScheduledClientMessageState state = state("state_transaction_in_progress", 0, null);
        state.setLockedUntil(now.plusMinutes(1));

        assertFalse(policy.isActionable(state, now, 3, 60));

        state.setLockedUntil(now.minusSeconds(1));
        assertTrue(policy.isActionable(state, now, 3, 60));
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
