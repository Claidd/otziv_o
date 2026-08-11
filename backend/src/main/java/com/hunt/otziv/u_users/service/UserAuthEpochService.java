package com.hunt.otziv.u_users.service;

import com.hunt.otziv.mobile_push.repository.MobilePushTokenRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class UserAuthEpochService {

    static final String PASSWORD_CHANGED = "PASSWORD_CHANGED"; // gitleaks:allow -- audit reason, not a credential
    static final String SECURITY_ROLES_CHANGED = "SECURITY_ROLES_CHANGED";
    static final String USER_DEACTIVATED = "USER_DEACTIVATED";
    static final String USER_REACTIVATED = "USER_REACTIVATED";

    private final UserRepository userRepository;
    private final MobilePushTokenRepository pushTokenRepository;

    @Transactional
    public void passwordChanged(User user) {
        rotate(user, PASSWORD_CHANGED, DeactivationChange.KEEP);
    }

    @Transactional
    public void securityRolesChanged(User user) {
        rotate(user, SECURITY_ROLES_CHANGED, DeactivationChange.KEEP);
    }

    @Transactional
    public void deactivated(User user) {
        user.setActive(false);
        rotate(user, USER_DEACTIVATED, DeactivationChange.SET);
    }

    @Transactional
    public void reactivated(User user) {
        user.setActive(true);
        rotate(user, USER_REACTIVATED, DeactivationChange.CLEAR);
    }

    private void rotate(User user, String reason, DeactivationChange deactivationChange) {
        Objects.requireNonNull(user, "user");
        if (user.getId() == null) {
            throw new IllegalArgumentException("A persisted user is required for auth epoch rotation.");
        }
        if (user.getAuthEpoch() < 0 || user.getAuthEpoch() == Long.MAX_VALUE) {
            throw new IllegalStateException("User auth epoch cannot be incremented safely.");
        }

        Long actorUserId = currentActorUserId(user);
        user.setAuthEpoch(user.getAuthEpoch() + 1L);

        if (deactivationChange == DeactivationChange.SET) {
            user.setDeactivatedAt(LocalDateTime.now());
            user.setDeactivatedByUserId(actorUserId);
            user.setDeactivationReason(reason);
        } else if (deactivationChange == DeactivationChange.CLEAR || user.isActive()) {
            user.setDeactivatedAt(null);
            user.setDeactivatedByUserId(null);
            user.setDeactivationReason(null);
        }

        pushTokenRepository.revokeAllActiveForUser(
                user.getId(),
                Instant.now(),
                reason,
                actorUserId
        );
    }

    private Long currentActorUserId(User targetUser) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }

        String username = authentication.getName();
        if (username == null || username.isBlank()) {
            return null;
        }
        if (username.equals(targetUser.getUsername())) {
            return targetUser.getId();
        }
        return userRepository.findIdByUsername(username).orElse(null);
    }

    private enum DeactivationChange {
        KEEP,
        SET,
        CLEAR
    }
}
