package com.hunt.otziv.maxbot.api;

/** Read-only access diagnosis; never joins a group or sends a message. */
public interface MaxChatAccess {
    record Result(String status, String reason) { }
    Result check(long chatId);
}
