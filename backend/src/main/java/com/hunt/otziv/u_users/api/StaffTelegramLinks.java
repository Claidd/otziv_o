package com.hunt.otziv.u_users.api;

import java.util.Optional;

/**
 * Identity-owned binding of a staff member's private Telegram chat.
 * A chat is bound only through a one-time deep link issued from the authenticated cabinet.
 */
public interface StaffTelegramLinks {

    /**
     * Handles {@code /start staff_<token>} in a private chat.
     * Empty when the message is not a staff link command; otherwise the reply text.
     */
    Optional<String> handleStartCommand(long chatId, String messageText);
}
