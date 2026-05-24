package com.hunt.otziv.maxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class MaxGroupLinkService {

    private static final String PAYLOAD_PREFIX = "c";
    private static final int SIGNATURE_LENGTH = 12;
    private static final Pattern MAX_WEB_CHAT_URL = Pattern.compile(
            "(?i)^https?://web\\.max\\.ru/(-?\\d{5,})(?:[/?#].*)?$"
    );
    private static final Pattern MAX_JOIN_URL = Pattern.compile(
            "(?i)^https?://(?:web\\.)?max\\.ru/join/([A-Za-z0-9_-]{8,})(?:[/?#].*)?$"
    );

    private final CompanyRepository companyRepository;
    private final MaxBotClient maxBotClient;

    @Value("${max.bot.username:}")
    private String botUsername;

    @Value("${max.bot.link-secret:${max.bot.token:otziv-max-link}}")
    private String linkSecret;

    public boolean isMaxChatUrl(String url) {
        if (!hasText(url)) {
            return false;
        }

        String normalized = url.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("max.ru/")
                || normalized.startsWith("max://");
    }

    public boolean isMaxGroupLinked(Company company) {
        return company != null && company.getMaxGroupChatId() != null;
    }

    public boolean isMaxGroupLinked(Long maxGroupChatId) {
        return maxGroupChatId != null;
    }

    public String buildInviteUrl(Company company) {
        if (company == null) {
            return "";
        }

        return buildInviteUrl(company.getId(), company.getUrlChat(), company.getMaxGroupChatId());
    }

    public String buildInviteUrl(Long companyId, String urlChat, Long maxGroupChatId) {
        if (companyId == null || !isMaxChatUrl(urlChat) || isMaxGroupLinked(maxGroupChatId)) {
            return "";
        }

        String username = normalizedBotUsername();
        if (!hasText(username)) {
            log.debug("MAX group invite link is unavailable: max.bot.username is empty");
            return "";
        }

        return "https://max.ru/" + username + "?start=" + payloadForCompany(companyId);
    }

    public Optional<String> handleBotStarted(Long userId, String payload) {
        if (userId == null || !hasText(payload) || !payload.startsWith(PAYLOAD_PREFIX)) {
            return Optional.empty();
        }

        Long companyId = parseCompanyId(payload);
        if (companyId == null || !payload.equals(payloadForCompany(companyId))) {
            log.warn("MAX group link rejected: invalid payload '{}', userId={}", payload, userId);
            return Optional.of("Не удалось начать привязку MAX-группы: ссылка устарела или неверная.");
        }

        Company company = companyRepository.findById(companyId).orElse(null);
        if (company == null) {
            return Optional.of("Не удалось начать привязку MAX-группы: компания не найдена.");
        }

        company.setMaxLinkUserId(userId);
        company.setMaxLinkRequestedAt(LocalDateTime.now());
        companyRepository.save(company);

        String title = companyTitle(company);
        log.info("MAX group link prepared for company id={} title='{}' by userId={}", company.getId(), title, userId);
        return Optional.of("Привязка MAX начата для компании \"" + title + "\". Добавьте этого MAX-бота в группу администратором, чтобы я получил событие добавления и сохранил chatId автоматически.");
    }

    public Optional<String> handleBotAdded(Long chatId, Long userId) {
        if (chatId == null) {
            return Optional.empty();
        }

        Company company = findCompanyByMaxWebChatId(chatId);
        JsonNode chat = null;
        if (company == null) {
            chat = maxBotClient.getChat(chatId);
            company = findCompanyByMaxJoinLink(chatId, chat);
        }
        if (company == null && userId != null) {
            Company pendingCompany = companyRepository.findFirstByMaxLinkUserIdOrderByMaxLinkRequestedAtDesc(userId)
                    .orElse(null);
            if (pendingCompany != null && !pendingCompanyMatchesChat(pendingCompany, chatId, chat)) {
                log.warn("MAX bot added to chatId={} by userId={}, but pending company id={} has another MAX chat URL",
                        chatId, userId, pendingCompany.getId());
                return Optional.of("Бот добавлен, но ссылка этой группы не совпадает с компанией, по которой была открыта MAX-ссылка. Откройте MAX-ссылку в нужной карточке и добавьте бота в правильную группу.");
            }
            company = pendingCompany;
        }
        if (company == null) {
            log.warn("MAX bot added to chatId={} by userId={}, but no pending company link found", chatId, userId);
            return Optional.of("Бот добавлен, но компания не определена. Сначала откройте красную MAX-ссылку в карточке компании, затем добавьте бота в группу.");
        }

        company.setMaxGroupChatId(chatId);
        company.setMaxLinkUserId(null);
        company.setMaxLinkRequestedAt(null);
        companyRepository.save(company);

        String title = companyTitle(company);
        log.info("MAX group chatId={} linked to company id={} title='{}'", chatId, company.getId(), title);
        return Optional.of("Готово: MAX-группа привязана к компании \"" + title + "\".");
    }

    private Company findCompanyByMaxWebChatId(Long chatId) {
        String chatIdText = String.valueOf(chatId);
        List<Company> candidates = companyRepository.findTop3ByMaxGroupChatIdIsNullAndUrlChatContaining(chatIdText);
        if (candidates.size() == 1 && chatId.equals(maxWebChatId(candidates.getFirst().getUrlChat()).orElse(null))) {
            Company company = candidates.getFirst();
            log.info("MAX group link matched by web chat URL for company id={} chatId={}", company.getId(), chatId);
            return company;
        }

        for (Company candidate : candidates) {
            if (chatId.equals(maxWebChatId(candidate.getUrlChat()).orElse(null))) {
                log.info("MAX group link matched by web chat URL for company id={} chatId={}", candidate.getId(), chatId);
                return candidate;
            }
        }

        return null;
    }

    private Company findCompanyByMaxJoinLink(Long chatId, JsonNode chat) {
        if (chat == null) {
            return null;
        }

        Optional<String> chatJoinToken = maxJoinToken(chat.path("link").asText(null));
        if (chatJoinToken.isEmpty()) {
            return null;
        }

        String token = chatJoinToken.get();
        List<Company> candidates = companyRepository.findTop3ByMaxGroupChatIdIsNullAndUrlChatContaining(token);
        if (candidates.size() == 1 && token.equals(maxJoinToken(candidates.getFirst().getUrlChat()).orElse(null))) {
            Company company = candidates.getFirst();
            log.info("MAX group link matched by join URL for company id={} chatId={}", company.getId(), chatId);
            return company;
        }

        for (Company candidate : candidates) {
            if (token.equals(maxJoinToken(candidate.getUrlChat()).orElse(null))) {
                log.info("MAX group link matched by join URL for company id={} chatId={}", candidate.getId(), chatId);
                return candidate;
            }
        }

        return null;
    }

    private boolean pendingCompanyMatchesChat(Company company, Long chatId, JsonNode chat) {
        Optional<Long> companyWebChatId = maxWebChatId(company.getUrlChat());
        if (companyWebChatId.isPresent() && !companyWebChatId.get().equals(chatId)) {
            return false;
        }

        Optional<String> companyJoinToken = maxJoinToken(company.getUrlChat());
        Optional<String> actualJoinToken = chat == null
                ? Optional.empty()
                : maxJoinToken(chat.path("link").asText(null));
        return companyJoinToken.isEmpty()
                || actualJoinToken.isEmpty()
                || companyJoinToken.get().equals(actualJoinToken.get());
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

    private String signature(Long companyId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes(), "HmacSHA256"));
            byte[] digest = mac.doFinal(("max-group:" + companyId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(digest)
                    .substring(0, SIGNATURE_LENGTH);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign MAX group link", e);
        }
    }

    private byte[] secretBytes() {
        String secret = hasText(linkSecret) ? linkSecret.trim() : "otziv-max-link";
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    private String normalizedBotUsername() {
        if (!hasText(botUsername)) {
            return "";
        }

        return botUsername.trim().replaceFirst("^@", "");
    }

    private static Optional<Long> maxWebChatId(String value) {
        if (!hasText(value)) {
            return Optional.empty();
        }

        Matcher matcher = MAX_WEB_CHAT_URL.matcher(value.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }

        try {
            return Optional.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> maxJoinToken(String value) {
        if (!hasText(value)) {
            return Optional.empty();
        }

        Matcher matcher = MAX_JOIN_URL.matcher(value.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }

        return Optional.of(matcher.group(1));
    }

    private static String companyTitle(Company company) {
        return hasText(company.getTitle()) ? company.getTitle().trim() : "Компания";
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
