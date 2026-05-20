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
import java.util.Locale;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class TelegramGroupLinkService {

    private static final String PAYLOAD_PREFIX = "c";
    private static final int SIGNATURE_LENGTH = 12;

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

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
