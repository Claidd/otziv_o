package com.hunt.otziv.performers.service;

import com.hunt.otziv.performers.model.PerformerProfile;
import com.hunt.otziv.performers.model.PerformerProfileStatus;
import com.hunt.otziv.performers.repository.PerformerProfileRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PerformerTelegramLinkService {

    private static final String TOKEN_PREFIX = "performer_";

    private final PerformerProfileRepository performerProfileRepository;
    private final UserRepository userRepository;

    @Transactional
    public Optional<String> handleStartCommand(long chatId, String messageText) {
        String token = extractToken(messageText);
        if (token == null) {
            return Optional.empty();
        }

        PerformerProfile performer = performerProfileRepository.findByTelegramLinkToken(token)
                .orElse(null);
        if (performer == null) {
            return Optional.of("Не удалось привязать Telegram: ссылка устарела или неверная.");
        }

        User user = performer.getUser();
        user.setTelegramChatId(chatId);
        userRepository.save(user);

        performer.setTelegramLinkedAt(LocalDateTime.now());
        performer.setTelegramLinkToken(null);
        performer.setLastActiveAt(LocalDateTime.now());
        if (performer.getStatus() == PerformerProfileStatus.NEW) {
            performer.setStatus(PerformerProfileStatus.ACTIVE);
        }
        performerProfileRepository.save(performer);

        String name = hasText(user.getFio()) ? user.getFio().trim() : user.getUsername();
        return Optional.of("Telegram привязан. Добро пожаловать, " + name + "! Задания будут приходить сюда и в личный кабинет.");
    }

    private String extractToken(String messageText) {
        if (!hasText(messageText)) {
            return null;
        }
        String[] parts = messageText.trim().split("\\s+", 2);
        if (parts.length < 2 || !parts[0].startsWith("/start")) {
            return null;
        }
        String token = parts[1].trim();
        return token.startsWith(TOKEN_PREFIX) ? token : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
