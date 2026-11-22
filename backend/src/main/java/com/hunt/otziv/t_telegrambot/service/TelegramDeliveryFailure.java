package com.hunt.otziv.t_telegrambot.service;

import java.util.Locale;
import java.util.Set;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

/** Bounded diagnostic labels only: provider bodies may contain bot tokens or client text. */
record TelegramDeliveryFailure(Integer apiCode, String reason) {
    /** Chat access refusals: Telegram rejected the request itself, so nothing reached the chat. */
    static final Set<String> CHAT_ACCESS_REFUSALS =
            Set.of("chat_not_found", "bot_removed", "bot_blocked", "chat_permission_denied");

    static TelegramDeliveryFailure describe(TelegramApiException failure) {
        if (failure instanceof TelegramApiRequestException request) {
            Integer code = request.getErrorCode();
            if (code != null && (code < 100 || code > 599)) code = null;
            String text = String.valueOf(request.getApiResponse()).toLowerCase(Locale.ROOT);
            String reason;
            if (request.getParameters() != null && request.getParameters().getMigrateToChatId() != null)
                reason = "chat_migrated";
            else if (Integer.valueOf(429).equals(code)) reason = "rate_limited";
            else if (text.contains("chat not found")) reason = "chat_not_found";
            else if (text.contains("bot was kicked") || text.contains("bot is not a member")) reason = "bot_removed";
            else if (text.contains("bot was blocked")) reason = "bot_blocked";
            else if (text.contains("not enough rights") || text.contains("have no rights")) reason = "chat_permission_denied";
            else if (text.contains("can't parse entities")) reason = "invalid_message_entities";
            else if (code != null && code >= 500) reason = "provider_error";
            else reason = "request_failed";
            return new TelegramDeliveryFailure(code, reason);
        }
        return new TelegramDeliveryFailure(null, "transport_or_response_failure");
    }

    /**
     * Only an explicit 400/403 answer about chat access proves the message was not delivered.
     * Timeouts, 429, 5xx and unreadable answers stay ambiguous: the message may have arrived.
     */
    boolean definitiveRefusal() {
        return (Integer.valueOf(400).equals(apiCode) || Integer.valueOf(403).equals(apiCode))
                && CHAT_ACCESS_REFUSALS.contains(reason);
    }
}
