package com.hunt.otziv.u_users.api;

import java.util.List;
import java.util.Optional;
import org.springframework.security.core.GrantedAuthority;

/** Active identities and shared Telegram groups for a pending payment report. */
public interface SpecialistPaymentReviewDirectory {
    record Actor(String username, List<GrantedAuthority> authorities) { }

    Optional<Long> activeUserIdByUsername(String username);

    Optional<Long> activePersonalTelegramChatId(Long userId);

    Optional<Long> activePaymentReviewGroupChatId(Long userId, String recipientType);

    Optional<Actor> activeActor(Long userId);

    /**
     * Working Telegram group of the active manager who marked a transfer to the owner. Owner transfers have
     * no recipient user; the notice goes where owner approvals used to go, the manager's group.
     */
    Optional<Long> activeOwnerReceiptGroupChatId(Long requesterUserId);

    /** True for an active owner or administrator whose Telegram account is linked to this user ID. */
    boolean activeOwnerOrAdminTelegramUser(Long telegramUserId);
}
