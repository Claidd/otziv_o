package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ManagerAutomationFailurePolicy {

    private static final Set<String> IMMEDIATE_ERROR_CODES = Set.of(
            "payment_instruction_failed",
            "whatsapp_group_missing",
            "telegram_group_missing",
            "max_group_missing",
            "chat_platform_unknown",
            "whatsapp_client_missing",
            "unknown_client",
            "missing_client",
            "empty_client_url",
            "missing_group_id",
            "message_empty",
            "missing_message",
            "company_missing"
    );

    private static final Set<String> TRANSIENT_ERROR_CODES = Set.of(
            "rate_limited",
            "daily_limit",
            "outside_messaging_window",
            "messaging_window_closed"
    );

    public boolean isActionable(
            ScheduledClientMessageState state,
            LocalDateTime now,
            int failureThreshold,
            int manualControlAfterMinutes
    ) {
        if (state == null || state.getStatus() != ScheduledMessageStateStatus.ACTIVE) {
            return false;
        }
        String code = normalize(state.getLastErrorCode());
        if (isExpectedControlState(code)) {
            return false;
        }
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        if (TRANSIENT_ERROR_CODES.contains(code)
                && state.getNextAttemptAt() != null
                && state.getNextAttemptAt().isAfter(effectiveNow)) {
            return false;
        }
        if (IMMEDIATE_ERROR_CODES.contains(code)) {
            return true;
        }
        int safeThreshold = Math.max(1, failureThreshold);
        if (state.getConsecutiveFailures() >= safeThreshold) {
            return true;
        }
        if (code.isBlank() || TRANSIENT_ERROR_CODES.contains(code)) {
            return false;
        }
        LocalDateTime attemptAt = state.getLastAttemptAt();
        int safeMinutes = Math.max(1, manualControlAfterMinutes);
        return attemptAt != null && !attemptAt.isAfter(effectiveNow.minusMinutes(safeMinutes));
    }

    private boolean isExpectedControlState(String code) {
        return code.isBlank()
                || code.contains("dry_run")
                || code.contains("review_recovery_active")
                || code.contains("order_status_changed")
                || code.contains("status_change")
                || code.contains("auto_archive")
                || code.contains("auto_ban")
                || "client_message_state_auto_recovered".equals(code);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
