package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.api.ClientMessageScenarioSwitches;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.config.settings.api.BackgroundAutomationSettings;
import java.time.LocalDateTime;
import static com.hunt.otziv.config.settings.api.BackgroundAutomationSettings.*;

/** Shared, fresh permissions for background work; unavailable settings never grant permission. */
final class ClientMessageAutomationPolicy {
    private ClientMessageAutomationPolicy() { }

    /** Master switch and pause only: for automatic senders without a scenario toggle (campaigns). */
    static boolean masterAllowed(BackgroundAutomationSettings settings, LocalDateTime now) {
        try {
            if (!settings.getBooleanFreshFailClosed(CLIENT_MESSAGES_WORKER_ENABLED, true)) return false;
            String pause = settings.getStringFresh(CLIENT_MESSAGES_PAUSED_UNTIL, null);
            return pause == null || pause.isBlank() || !LocalDateTime.parse(pause).isAfter(now);
        } catch (RuntimeException unavailable) { return false; }
    }

    static boolean allowed(BackgroundAutomationSettings settings, ClientMessageScenario scenario, LocalDateTime now) {
        return stopReason(settings, scenario, now) == null;
    }

    /**
     * Why the autoresponder does not run this scenario now, in the words of the settings page; null when it may run.
     * Unavailable settings never grant permission.
     */
    static String stopReason(BackgroundAutomationSettings settings, ClientMessageScenario scenario, LocalDateTime now) {
        try {
            if (!settings.getBooleanFreshFailClosed(CLIENT_MESSAGES_WORKER_ENABLED, true))
                return "выключен переключатель «" + ClientMessageScenarioSwitches.MASTER_SWITCH + "»";
            String pause = settings.getStringFresh(CLIENT_MESSAGES_PAUSED_UNTIL, null);
            if (pause != null && !pause.isBlank() && LocalDateTime.parse(pause).isAfter(now))
                return "автоответчик на паузе: защита от массовых ошибок остановила отправку, пауза снимется сама";
            String key = switchKey(scenario);
            if (key == null || settings.getBooleanFreshFailClosed(key, true)) return null;
            return "выключен переключатель «" + ClientMessageScenarioSwitches.switchTitle(scenario.name()) + "»";
        } catch (RuntimeException unavailable) {
            return "настройки автоответчика сейчас недоступны";
        }
    }

    private static String switchKey(ClientMessageScenario scenario) {
        return switch (scenario) {
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
    }
}
