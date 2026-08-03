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
import java.util.Objects;
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

        PerformerProfile performer = performerProfileRepository.findByTelegramLinkTokenForUpdate(token)
                .orElse(null);
        if (performer == null) {
            return Optional.of("Не удалось привязать Telegram: ссылка устарела или неверная.");
        }

        LocalDateTime now = LocalDateTime.now();
        if (performer.getStatus() != PerformerProfileStatus.NEW
                || performer.getRegistrationExpiresAt() == null
                || !performer.getRegistrationExpiresAt().isAfter(now)) {
            performer.setTelegramLinkToken(null);
            if (performer.getStatus() == PerformerProfileStatus.NEW) {
                performer.setStatus(PerformerProfileStatus.REJECTED);
                performer.setBlockReason("Срок публичной заявки истёк");
            }
            performerProfileRepository.save(performer);
            return Optional.of("Не удалось привязать Telegram: срок заявки истёк. Отправьте новую заявку.");
        }

        User user = performer.getUser();
        Optional<User> existingChatOwner = userRepository.findByTelegramChatId(chatId);
        if (existingChatOwner.isPresent() && !Objects.equals(existingChatOwner.get().getId(), user.getId())) {
            return Optional.of("Этот Telegram уже привязан к другой учётной записи. Обратитесь к администратору.");
        }
        user.setTelegramChatId(chatId);
        userRepository.save(user);

        performer.setTelegramLinkedAt(now);
        performer.setTelegramLinkToken(null);
        performerProfileRepository.save(performer);

        String name = hasText(user.getFio()) ? user.getFio().trim() : user.getUsername();
        return Optional.of("Telegram привязан, " + name + ". Заявка ожидает ручной проверки телефона и активации администратором.");
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
