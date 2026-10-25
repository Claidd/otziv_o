package com.hunt.otziv.client_chat_control.api;

import java.time.LocalDateTime;

/** Read-only chat-owner seam. Caller supplies the server-authorized frozen destination, never HTTP user input. */
public interface ReminderContactHistory {
    /** Latest contact in complete provider history; throws when unavailable. No receipt/non-delivery inference. */
    LocalDateTime latestReminderContact(String clientId, String chatId, LocalDateTime since);
    /** Complete provider lookup plus unresolved tracked replies; unavailable history throws. */
    boolean hasReplyBlockingArchive(String clientId, String chatId, LocalDateTime since);
    /** Current tracked reply check, repeated while holding the order lock. */
    boolean hasOpenReply(String chatId);
}
