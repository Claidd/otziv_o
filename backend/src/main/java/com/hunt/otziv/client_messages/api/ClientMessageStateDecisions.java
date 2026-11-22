package com.hunt.otziv.client_messages.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only answers about an autoresponder task that other modules (manager control) must respect, so a repair or a
 * remark never contradicts a decision the autoresponder already recorded. The autoresponder itself uses the same rules
 * (ClientMessageStateSafety delegates here).
 */
public final class ClientMessageStateDecisions {

    /** Closed by an administrator from the monitor («Пометить выполненным»). */
    public static final String MANUAL_DONE = "manual_done";
    /** Disabled by an administrator from the monitor («Отключить»). */
    public static final String MANUAL_DISABLED = "manual_disabled";
    /** WhatsApp confirmed that it did not send the prepared message. */
    public static final String CONFIRMED_NOT_DISPATCHED = "whatsapp_not_dispatched";

    /**
     * A task that waits on purpose and tries again by itself: the manager's own message covers the cycle, the daily
     * limit or the sender interval is used up, the messaging window is closed, reviews are being restored.
     */
    public static final Set<String> INTENTIONAL_WAIT_CODES = Set.of(
            "manual_contact_reserved",
            "daily_limit",
            "rate_limited",
            "outside_messaging_window",
            "messaging_window_closed",
            "review_recovery_active"
    );

    /** A task postponed because its scenario, or the whole autoresponder, is switched off or paused in the settings. */
    public static final Set<String> SWITCHED_OFF_CODES = Set.of(
            "automation_paused",
            "bad_review_invoice_disabled",
            "bad_review_auto_ban_disabled"
    );

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String GATEWAY_RESPONSE = ". Ответ: ";

    private ClientMessageStateDecisions() {
    }

    /**
     * An administrator closed or disabled this task by hand. Within the same order cycle (same target key) no automatic
     * path may reopen it: neither the reconciliation, nor a repeated event, nor «Починить» of the manager control.
     * A new cycle gets a new target key and therefore a new task.
     */
    public static boolean isManualDecision(ScheduledClientMessageState state) {
        if (state == null) {
            return false;
        }
        String code = state.getLastErrorCode();
        return (state.getStatus() == ScheduledMessageStateStatus.DONE && MANUAL_DONE.equals(code))
                || (state.getStatus() == ScheduledMessageStateStatus.DISABLED && MANUAL_DISABLED.equals(code));
    }

    public static boolean isManualDecisionCode(String code) {
        String normalized = normalize(code);
        return MANUAL_DONE.equals(normalized) || MANUAL_DISABLED.equals(normalized);
    }

    /** WhatsApp refused the message before sending it; the task waits in the queue for a new attempt. */
    public static boolean isQueuedAfterWhatsAppRefusal(ScheduledClientMessageState state) {
        return state != null && "RETRYABLE".equals(state.getDeliveryStatus()) && state.getNextAttemptAt() != null
                && (CONFIRMED_NOT_DISPATCHED.equals(state.getLastErrorCode())
                    || isExplicitWhatsAppRefusal(state.getLastErrorCode(), state.getLastErrorMessage()));
    }

    /** The gateway answered «not dispatched» explicitly (attempts recorded before the refusal had its own code). */
    public static boolean isExplicitWhatsAppRefusal(String code, String diagnostic) {
        if (!"gateway_not_ready".equals(code) || diagnostic == null) return false;
        int start = diagnostic.indexOf(GATEWAY_RESPONSE);
        if (start < 0) return false;
        try {
            JsonNode body = JSON.readTree(diagnostic.substring(start + GATEWAY_RESPONSE.length()));
            return body != null && "error".equals(body.path("status").asText())
                    && !body.path("code").asText("").isBlank()
                    && body.path("dispatched").isBoolean() && !body.path("dispatched").booleanValue();
        } catch (JsonProcessingException invalid) {
            return false;
        }
    }

    public static boolean isIntentionalWaitCode(String code) {
        return INTENTIONAL_WAIT_CODES.contains(normalize(code));
    }

    /**
     * An active task with its next attempt planned that only waits on purpose: healthy, nothing to repair. A wait that
     * follows real failures is not: the failures still need attention (the overdue lists count them the same way).
     */
    public static boolean isIntentionalWait(ScheduledClientMessageState state) {
        return state != null
                && state.getStatus() == ScheduledMessageStateStatus.ACTIVE
                && state.getNextAttemptAt() != null
                && state.getConsecutiveFailures() == 0
                && isIntentionalWaitCode(state.getLastErrorCode());
    }

    /** Minutes a planned attempt may be overdue before its wait stops counting as healthy. */
    public static final int INTENTIONAL_WAIT_OVERDUE_MINUTES = 60;

    /**
     * {@link #isIntentionalWait(ScheduledClientMessageState)} whose planned attempt is not long overdue: when the whole
     * autoresponder is stopped, nothing rewrites the task, so an old wait must not hide the order from the manager.
     */
    public static boolean isIntentionalWait(ScheduledClientMessageState state, java.time.LocalDateTime now) {
        return isIntentionalWait(state)
                && (now == null || !state.getNextAttemptAt().isBefore(now.minusMinutes(INTENTIONAL_WAIT_OVERDUE_MINUTES)));
    }

    /**
     * The autoresponder still handles this unconfirmed send itself: within 24 hours (plus a margin for the recovery
     * pass) it sends it once more as a new operation. Until then manager control shows no card; afterwards, or when
     * that automatic send was already used, the manager decides. Archive offers and bad-review invoices have their
     * own recovery and are not covered.
     */
    public static boolean awaitsAutomaticResend(ScheduledClientMessageState state, java.time.LocalDateTime now) {
        return state != null && now != null
                && state.getStatus() == ScheduledMessageStateStatus.ACTIVE
                && "UNKNOWN".equals(state.getDeliveryStatus())
                && "state_transaction_outcome_uncertain".equals(state.getLastErrorCode())
                && state.getUncertainAutoResendAt() == null
                && state.getScenario() != com.hunt.otziv.client_messages.model.ClientMessageScenario.ARCHIVE_REORDER_OFFER
                && state.getScenario() != com.hunt.otziv.client_messages.model.ClientMessageScenario.BAD_REVIEW_INVOICE
                // Invoice and review-check messages are not re-sent automatically (one operation id until confirmed).
                && state.getScenario() != com.hunt.otziv.client_messages.model.ClientMessageScenario.PAYMENT_INVOICE_RETRY
                && state.getScenario() != com.hunt.otziv.client_messages.model.ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY
                && state.getDeliveryPreparedAt() != null
                && state.getDeliveryPreparedAt().isAfter(now.minusHours(25));
    }

    public static boolean isSwitchedOffCode(String code) {
        return SWITCHED_OFF_CODES.contains(normalize(code));
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
    }
}
