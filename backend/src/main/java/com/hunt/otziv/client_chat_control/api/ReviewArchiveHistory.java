package com.hunt.otziv.client_chat_control.api;

import java.time.LocalDateTime;

/** A read-only, channel-specific proof. Missing history never proves silence. */
public interface ReviewArchiveHistory {
    record Chat(String platform, String clientId, String chatId) { }
    enum Outcome { CLEAR, REPLY_FOUND, HISTORY_MISSING, HISTORY_UNAVAILABLE }
    record Check(Outcome outcome, String reason, LocalDateTime since, LocalDateTime checkedAt,
                 int messages, String source) { }
    interface Reader {
        String platform();
        Check read(Chat chat, LocalDateTime since);
    }
    Check inspect(Chat chat, LocalDateTime since);
    /** Repeated inside the order transaction after the transport lookup. */
    boolean hasLocalReply(Chat chat, LocalDateTime since);
}
