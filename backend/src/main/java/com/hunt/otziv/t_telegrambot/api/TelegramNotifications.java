package com.hunt.otziv.t_telegrambot.api;

/** Best-effort text notification to a recipient selected by an authorized application scenario. */
public interface TelegramNotifications {
    boolean sendText(long chatId, String text);

    /** One Bot API call with a confirmed result, for retryable delivery intents. */
    boolean sendTextOnce(long chatId, String text);
}
