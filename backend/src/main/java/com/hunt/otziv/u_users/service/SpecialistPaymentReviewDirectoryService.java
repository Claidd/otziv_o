package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.api.SpecialistPaymentReviewDirectory;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
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
