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
}
