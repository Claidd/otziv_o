package com.hunt.otziv.t_telegrambot.api;

/** Read-only access check for the current bot. Never joins, migrates, or sends to a chat. */
public interface TelegramChatAccess {
    record Result(String status, String reason) { }
    Result check(long chatId);
}
