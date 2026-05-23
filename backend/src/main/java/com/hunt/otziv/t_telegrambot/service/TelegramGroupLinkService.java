package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class TelegramGroupLinkService {

    private static final String PAYLOAD_PREFIX = "c";
    private static final int SIGNATURE_LENGTH = 12;
    private static final Pattern TELEGRAM_PUBLIC_CHAT_URL = Pattern.compile(
            "(?i)^(?:https?://)?(?:t\\.me|telegram\\.me|telegram\\.dog)/@?([A-Za-z0-9_]{5,32})(?:[/?#].*)?$"
    );
    private static final Pattern TELEGRAM_RESOLVE_URL = Pattern.compile(
            "(?i)^tg://resolve\\?(?:.*&)?domain=([A-Za-z0-9_]{5,32})(?:&.*)?$"
    );

    private final CompanyRepository companyRepository;

    @Value("${telegram.bot.username:}")
    private String botUsername;

    @Value("${telegram.bot.link-secret:${telegram.bot.token:otziv-telegram-link}}")
    private String linkSecret;

    public boolean isTelegramChatUrl(String url) {
        if (!hasText(url)) {
            return false;
        }

        String normalized = url.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("t.me/")
                || normalized.contains("telegram.me/")
                || normalized.contains("telegram.dog/")
                || normalized.startsWith("tg://")
                || normalized.contains("telegram");
    }

    public boolean isTelegramGroupLinked(Company company) {
        return company != null && company.getTelegramGroupChatId() != null;
    }

    public boolean isTelegramGroupLinked(Long telegramGroupChatId) {
        return telegramGroupChatId != null;
    }

    public String buildInviteUrl(Company company) {
        if (company == null) {
            return "";
        }

        return buildInviteUrl(company.getId(), company.getUrlChat(), company.getTelegramGroupChatId());
    }

    public String buildInviteUrl(Long companyId, String urlChat, Long telegramGroupChatId) {
        if (companyId == null || !isTelegramChatUrl(urlChat) || isTelegramGroupLinked(telegramGroupChatId)) {
            return "";
        }

        String username = normalizedBotUsername();
        if (!hasText(username)) {
            log.warn("Telegram group invite link is unavailable: telegram.bot.username is empty");
            return "";
        }

        return "https://t.me/" + username + "?startgroup=" + payloadForCompany(companyId);
    }

    public Optional<String> handleBotAddedToGroup(long chatId, String chatUsername, String chatTitle) {
        if (chatId > 0 || !hasText(chatUsername)) {
            return Optional.empty();
        }

        String username = normalizeUsername(chatUsername);
        if (!hasText(username)) {
            return Optional.empty();
        }

        List<Company> candidates = companyRepository.findTop3ByTelegramGroupChatIdIsNullAndUrlChatContainingIgnoreCase(username);
        Company company = matchCompanyByPublicUsername(username, candidates);
        if (company == null) {
            log.info("Telegram bot added to group chatId={} username='{}' title='{}', but no company matched by public URL",
                    chatId, username, chatTitle);
            return Optional.empty();
        }

        company.setTelegramGroupChatId(chatId);
        companyRepository.save(company);
        log.info("Telegram group chatId={} linked by public username @{} to company id={} title='{}'",
                chatId, username, company.getId(), company.getTitle());

        String title = hasText(company.getTitle()) ? company.getTitle().trim() : "Компания";
        return Optional.of("Готово: Telegram-группа привязана к компании \"" + title + "\".");
    }

    public Optional<String> handleGroupStartCommand(long chatId, String messageText) {
        String payload = extractStartPayload(messageText);
        if (!hasText(payload) || !payload.startsWith(PAYLOAD_PREFIX)) {
            return Optional.empty();
        }

        if (chatId > 0) {
            return Optional.of("Эта ссылка нужна для группы. Добавьте бота в Telegram-группу компании через кнопку в карточке.");
        }

        Long companyId = parseCompanyId(payload);
        if (companyId == null || !payload.equals(payloadForCompany(companyId))) {
            log.warn("Telegram group link rejected: invalid payload '{}', chatId={}", payload, chatId);
            return Optional.of("Не удалось привязать группу: ссылка устарела или неверная.");
        }

        Company company = companyRepository.findById(companyId).orElse(null);
        if (company == null) {
            return Optional.of("Не удалось привязать группу: компания не найдена.");
        }

        company.setTelegramGroupChatId(chatId);
        companyRepository.save(company);
        log.info("Telegram group chatId={} linked to company id={} title='{}'", chatId, company.getId(), company.getTitle());

        String title = hasText(company.getTitle()) ? company.getTitle().trim() : "Компания";
        return Optional.of("Готово: Telegram-группа привязана к компании \"" + title + "\".");
    }

    private Company matchCompanyByPublicUsername(String username, List<Company> candidates) {
        if (candidates.size() == 1 && username.equals(telegramPublicUsername(candidates.getFirst().getUrlChat()).orElse(null))) {
            return candidates.getFirst();
        }

        for (Company candidate : candidates) {
            if (username.equals(telegramPublicUsername(candidate.getUrlChat()).orElse(null))) {
                return candidate;
            }
        }

        return null;
    }

    private String payloadForCompany(Long companyId) {
        return PAYLOAD_PREFIX + companyId + "_" + signature(companyId);
    }

    private Long parseCompanyId(String payload) {
        int separator = payload.indexOf('_');
        if (separator <= PAYLOAD_PREFIX.length()) {
            return null;
        }

        try {
            return Long.parseLong(payload.substring(PAYLOAD_PREFIX.length(), separator));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractStartPayload(String messageText) {
        if (!hasText(messageText)) {
            return "";
        }

        String[] parts = messageText.trim().split("\\s+", 2);
        if (parts.length < 2 || !parts[0].startsWith("/start")) {
            return "";
        }

        return parts[1].trim();
    }

    private String signature(Long companyId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes(), "HmacSHA256"));
            byte[] digest = mac.doFinal(("telegram-group:" + companyId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(digest)
                    .substring(0, SIGNATURE_LENGTH);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign Telegram group link", e);
        }
    }

    private byte[] secretBytes() {
        String secret = hasText(linkSecret) ? linkSecret.trim() : "otziv-telegram-link";
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    private String normalizedBotUsername() {
        if (!hasText(botUsername)) {
            return "";
        }

        return botUsername.trim().replaceFirst("^@", "");
    }

    private static Optional<String> telegramPublicUsername(String value) {
        if (!hasText(value)) {
            return Optional.empty();
        }

        String trimmed = value.trim();
        Matcher publicUrlMatcher = TELEGRAM_PUBLIC_CHAT_URL.matcher(trimmed);
        if (publicUrlMatcher.matches()) {
            return Optional.of(normalizeUsername(publicUrlMatcher.group(1)));
        }

        Matcher resolveUrlMatcher = TELEGRAM_RESOLVE_URL.matcher(trimmed);
        if (resolveUrlMatcher.matches()) {
            return Optional.of(normalizeUsername(resolveUrlMatcher.group(1)));
        }

        if (trimmed.matches("^@[A-Za-z0-9_]{5,32}$")) {
            return Optional.of(normalizeUsername(trimmed));
        }

        return Optional.empty();
    }

    private static String normalizeUsername(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.trim().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
