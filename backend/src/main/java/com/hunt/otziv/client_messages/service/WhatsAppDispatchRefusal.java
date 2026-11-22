package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.model.ScheduledClientMessageAttempt;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageAttemptStatus;
import java.util.List;
import java.util.Objects;

/** Compatibility with attempts recorded before the gateway refusal was shown on the control card. */
final class WhatsAppDispatchRefusal {

    static boolean explicit(String code, String diagnostic) {
        return com.hunt.otziv.client_messages.api.ClientMessageStateDecisions.isExplicitWhatsAppRefusal(code, diagnostic);
    }

    static boolean matchesRollback(ScheduledClientMessageState state, List<ScheduledClientMessageAttempt> attempts) {
        if (attempts.size() != 2) return false;
        var rollback = attempts.get(0);
        var denial = attempts.get(1);
        String cause = rollback.getErrorMessage();
        // Each transport call requires a committed PREPARED with a new preparation time.
        // Only a refusal in this preparation followed solely by a failed DB claim qualifies.
        return sameTarget(state, rollback) && sameTarget(state, denial)
                && rollback.getAttemptedAt().isAfter(denial.getAttemptedAt())
                && !denial.getAttemptedAt().isBefore(state.getDeliveryPreparedAt())
                && ClientMessageStateSafety.TRANSACTION_OUTCOME_UNCERTAIN.equals(rollback.getErrorCode())
                && cause != null && cause.startsWith("Транзакция обработки откатилась.")
                && cause.contains("Deadlock found when trying to get lock")
                && Objects.equals(cause, state.getLastErrorMessage())
                && state.getDeliveryMessage() != null && !state.getDeliveryMessage().isBlank()
                && state.getDeliveryMessage().equals(denial.getMessagePreview())
                && explicit(denial.getErrorCode(), denial.getErrorMessage());
    }

    private static boolean sameTarget(ScheduledClientMessageState state, ScheduledClientMessageAttempt attempt) {
        return attempt.getStatus() == ScheduledMessageAttemptStatus.FAILED
                && Objects.equals(state.getId(), attempt.getStateId()) && state.getScenario() == attempt.getScenario()
                && Objects.equals(state.getCompanyId(), attempt.getCompanyId()) && Objects.equals(state.getOrderId(), attempt.getOrderId())
                && Objects.equals(state.getTargetKey(), attempt.getTargetKey());
    }

    private WhatsAppDispatchRefusal() {}
}
