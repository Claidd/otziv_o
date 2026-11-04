package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.api.StaffTelegramLinks;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class StaffTelegramLinkService implements StaffTelegramLinks {

    static final String TOKEN_PREFIX = "staff_";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${telegram.bot.username:}")
    private String telegramBotUsername;

    public record IssuedLink(String url, LocalDateTime expiresAt) {
    }

    /** Issues a fresh one-time deep link for the authenticated user; older links stop working. */
    @Transactional
    public IssuedLink issue(String username) {
        if (!hasText(telegramBotUsername)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Telegram-бот не настроен");
        }
        User user = userRepository.findByUsername(username)
                .filter(User::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Пользователь не найден"));
        byte[] random = new byte[24];
        secureRandom.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        LocalDateTime expiresAt = LocalDateTime.now().plus(TOKEN_TTL);
        jdbcTemplate.update("DELETE FROM staff_telegram_link_tokens WHERE user_id = ? OR expires_at < ?",
                user.getId(), Timestamp.valueOf(LocalDateTime.now()));
        jdbcTemplate.update(
                "INSERT INTO staff_telegram_link_tokens (token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                sha256(token), user.getId(), Timestamp.valueOf(expiresAt)
        );
        String bot = telegramBotUsername.trim().replaceFirst("^@", "");
        return new IssuedLink("https://t.me/" + bot + "?start=" + TOKEN_PREFIX + token, expiresAt);
    }

    @Override
    @Transactional
    public Optional<String> handleStartCommand(long chatId, String messageText) {
        String token = extractToken(messageText);
        if (token == null) {
            return Optional.empty();
        }
        String hash = sha256(token);
        List<Long> owners = jdbcTemplate.queryForList(
                "SELECT user_id FROM staff_telegram_link_tokens WHERE token_hash = ? AND expires_at > ? FOR UPDATE",
                Long.class, hash, Timestamp.valueOf(LocalDateTime.now())
        );
        jdbcTemplate.update("DELETE FROM staff_telegram_link_tokens WHERE token_hash = ?", hash);
        if (owners.isEmpty()) {
            return Optional.of("Ссылка для привязки устарела или уже использована. "
                    + "Откройте личный кабинет и нажмите «Привязать Telegram» ещё раз.");
        }
        User user = userRepository.findById(owners.getFirst()).orElse(null);
        if (user == null || !user.isActive()) {
            return Optional.of("Учётная запись недоступна. Обратитесь к администратору.");
        }
        Optional<User> chatOwner = userRepository.findByTelegramChatId(chatId);
        if (chatOwner.isPresent() && !Objects.equals(chatOwner.get().getId(), user.getId())) {
            return Optional.of("Этот Telegram уже привязан к другой учётной записи. Обратитесь к администратору.");
        }
        user.setTelegramChatId(chatId);
        userRepository.save(user);
        log.info("STAFF_TELEGRAM_LINKED userId={}", user.getId());
        return Optional.of("Telegram привязан к учётной записи " + user.getUsername() + ".");
    }

    private static String extractToken(String messageText) {
        if (!hasText(messageText)) {
            return null;
        }
        String[] parts = messageText.trim().split("\\s+", 2);
        if (parts.length < 2 || !parts[0].startsWith("/start")) {
            return null;
        }
        String payload = parts[1].trim();
        if (!payload.startsWith(TOKEN_PREFIX) || payload.length() <= TOKEN_PREFIX.length() || payload.length() > 64) {
            return null;
        }
        return payload.substring(TOKEN_PREFIX.length());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
