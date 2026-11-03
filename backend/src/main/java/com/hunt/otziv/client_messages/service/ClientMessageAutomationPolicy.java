package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.config.settings.service.AppSettingService;
import java.time.LocalDateTime;
import static com.hunt.otziv.config.settings.service.AppSettingService.*;

/** Shared, fresh permissions for background work; unavailable settings never grant permission. */
final class ClientMessageAutomationPolicy {
    private ClientMessageAutomationPolicy() { }

    static boolean allowed(AppSettingService settings, ClientMessageScenario scenario, LocalDateTime now) {
        try {
            if (!settings.getBooleanFreshFailClosed(CLIENT_MESSAGES_WORKER_ENABLED, true)) return false;
            String pause = settings.getStringFresh(CLIENT_MESSAGES_PAUSED_UNTIL, null);
            if (pause != null && !pause.isBlank() && LocalDateTime.parse(pause).isAfter(now)) return false;
            String key = switch (scenario) {
                case CLIENT_TEXT_REMINDER -> CLIENT_MESSAGES_CLIENT_TEXT_REMINDER_ENABLED;
                case REVIEW_CHECK_REMINDER -> CLIENT_MESSAGES_REVIEW_CHECK_ENABLED;
                case REVIEW_CHECK_AUTO_ARCHIVE -> CLIENT_MESSAGES_REVIEW_CHECK_AUTO_ARCHIVE_ENABLED;
                case ARCHIVE_REORDER_OFFER -> CLIENT_MESSAGES_ARCHIVE_REORDER_ENABLED;
                case PAYMENT_REMINDER -> CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED;
                // Delivery retries are not reminder toggles; their existing event permissions remain in force.
                case PAYMENT_INVOICE_RETRY, REVIEW_CHECK_DELIVERY_RETRY -> null;
                case PAYMENT_OVERDUE_ESCALATION -> CLIENT_MESSAGES_PAYMENT_OVERDUE_ENABLED;
                case BAD_REVIEW_INVOICE -> CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED;
                case BAD_REVIEW_AUTO_BAN -> CLIENT_MESSAGES_BAD_REVIEW_AUTO_BAN_ENABLED;
                case REVIEW_RECOVERY_NOTICE -> CLIENT_MESSAGES_REVIEW_RECOVERY_NOTICE_ENABLED;
            };
            return key == null || settings.getBooleanFreshFailClosed(key, true);
        } catch (RuntimeException unavailable) { return false; }
    }
}
