package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.api.SpecialistPaymentReviewDirectory;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SpecialistPaymentReviewDirectoryService implements SpecialistPaymentReviewDirectory {
    private final UserRepository users;
    private final ManagerRepository managers;

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> activeUserIdByUsername(String username) {
        return users.findByUsername(username).filter(User::isActive).map(User::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> activePersonalTelegramChatId(Long userId) {
        return users.findById(userId).filter(User::isActive).map(User::getTelegramChatId)
                .filter(chatId -> chatId > 0);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> activePaymentReviewGroupChatId(Long userId, String recipientType) {
        if (userId == null) return Optional.empty();
        return users.findById(userId).filter(User::isActive).flatMap(user -> {
            Long group = "MANAGER".equals(recipientType)
                    ? managers.findByUserId(userId).map(manager -> manager.getAuditTelegramGroupChatId()).orElse(null)
                    : user.getWorkerTelegramGroupChatId();
            // A positive Telegram ID is a private chat, even if entered in the group field.
            return Optional.ofNullable(group).filter(chatId -> chatId < 0);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> activeOwnerReceiptGroupChatId(Long requesterUserId) {
        if (requesterUserId == null) return Optional.empty();
        return users.findById(requesterUserId).filter(User::isActive)
                .flatMap(user -> managers.findByUserId(requesterUserId))
                .map(manager -> manager.getAuditTelegramGroupChatId())
                .filter(chatId -> chatId < 0);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean activeOwnerOrAdminTelegramUser(Long telegramUserId) {
        if (telegramUserId == null || telegramUserId <= 0) return false;
        return java.util.stream.Stream.concat(users.findAllOwners("ROLE_OWNER").stream(),
                        users.findAllOwners("ROLE_ADMIN").stream())
                .filter(java.util.Objects::nonNull).filter(User::isActive)
                .anyMatch(user -> telegramUserId.equals(user.getTelegramChatId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Actor> activeActor(Long userId) {
        return users.findById(userId).filter(User::isActive)
                .filter(user -> user.getUsername() != null && user.getRoles() != null && !user.getRoles().isEmpty())
                .map(user -> {
                    List<GrantedAuthority> authorities = user.getRoles().stream()
                            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role.getAuthority()))
                            .toList();
                    return new Actor(user.getUsername(), authorities);
                });
    }
}
