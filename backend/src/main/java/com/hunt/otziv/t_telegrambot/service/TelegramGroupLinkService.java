package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.webhook.security.OneTimeGroupLinkTokenStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class TelegramGroupLinkService {

    private static final String PAYLOAD_PREFIX = "c";
    private static final String WORKER_PAYLOAD_PREFIX = "u";
    private static final String MANAGER_AUDIT_PAYLOAD_PREFIX = "m";
    private static final String COMPANY_SCOPE = "telegram-company-group";
    private static final String WORKER_SCOPE = "telegram-worker-group";
    private static final String MANAGER_AUDIT_SCOPE = "telegram-manager-audit-group";
    private static final Pattern TELEGRAM_PUBLIC_CHAT_URL = Pattern.compile(
            "(?i)^(?:https?://)?(?:t\\.me|telegram\\.me|telegram\\.dog)/@?([A-Za-z0-9_]{5,32})(?:[/?#].*)?$"
    );
    private static final Pattern TELEGRAM_RESOLVE_URL = Pattern.compile(
            "(?i)^tg://resolve\\?(?:.*&)?domain=([A-Za-z0-9_]{5,32})(?:&.*)?$"
    );

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final ManagerRepository managerRepository;
    private final OneTimeGroupLinkTokenStore tokenStore;

    @Autowired
    public TelegramGroupLinkService(
            CompanyRepository companyRepository,
            UserRepository userRepository,
            ManagerRepository managerRepository,
            OneTimeGroupLinkTokenStore tokenStore
    ) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.managerRepository = managerRepository;
        this.tokenStore = tokenStore;
    }

    public TelegramGroupLinkService(
            CompanyRepository companyRepository,
            UserRepository userRepository,
            ManagerRepository managerRepository
    ) {
        this(companyRepository, userRepository, managerRepository, new OneTimeGroupLinkTokenStore());
    }

    @Value("${telegram.bot.username:}")
    private String botUsername;

    @Value("${telegram.bot.link-secret:}")
    private String linkSecret;

    public boolean isTelegramChatUrl(String url) {
        if (!hasText(url)) {
            return false;
        }

        String normalized = url.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("startgroup=")) {
            return false;
        }
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
        if (!OneTimeGroupLinkTokenStore.isStrongSecret(linkSecret)) {
            log.error("Telegram group invite link is unavailable: telegram.bot.link-secret is missing or too short");
            return "";
        }

        return "https://t.me/" + username + "?startgroup=" + payloadForCompany(companyId);
    }

    public String buildWorkerInviteUrl(User user) {
        if (user == null || user.getId() == null || !isTelegramChatUrl(user.getWorkerChatUrl()) || isTelegramGroupLinked(user.getWorkerTelegramGroupChatId())) {
            return "";
        }

        String username = normalizedBotUsername();
        if (!hasText(username)) {
            log.warn("Telegram worker group invite link is unavailable: telegram.bot.username is empty");
            return "";
        }
        if (!OneTimeGroupLinkTokenStore.isStrongSecret(linkSecret)) {
            log.error("Telegram worker invite link is unavailable: telegram.bot.link-secret is missing or too short");
            return "";
        }

        return "https://t.me/" + username + "?startgroup=" + payloadForWorker(user.getId());
    }

    public String buildManagerAuditInviteUrl(Manager manager) {
        if (manager == null
                || manager.getId() == null
                || !isTelegramChatUrl(manager.getAuditTelegramGroupUrl())
                || isTelegramGroupLinked(manager.getAuditTelegramGroupChatId())) {
            return "";
        }
        String username = normalizedBotUsername();
        if (!hasText(username)) {
            log.warn("Telegram manager audit group invite link is unavailable: telegram.bot.username is empty");
            return "";
        }
        if (!OneTimeGroupLinkTokenStore.isStrongSecret(linkSecret)) {
            log.error("Telegram manager audit invite is unavailable: telegram.bot.link-secret is missing or too short");
            return "";
        }
        return "https://t.me/" + username + "?startgroup=" + payloadForManagerAudit(manager.getId());
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
            User workerUser = matchWorkerByPublicUsername(username, userRepository.findTop3ByWorkerTelegramGroupChatIdIsNullAndWorkerChatUrlContainingIgnoreCase(username));
            if (workerUser == null) {
                log.info("Telegram bot added to group chatId={} username='{}' title='{}', but no company or worker matched by public URL",
                        chatId, username, chatTitle);
                return Optional.empty();
            }
            workerUser.setWorkerTelegramGroupChatId(chatId);
            userRepository.save(workerUser);
            String workerName = hasText(workerUser.getFio()) ? workerUser.getFio().trim() : workerUser.getUsername();
            log.info("Telegram group chatId={} linked by public username @{} to worker user id={} username='{}'",
                    chatId, username, workerUser.getId(), workerUser.getUsername());
            return Optional.of("Готово: Telegram-группа привязана к специалисту \"" + workerName + "\".");
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
        if (!hasText(payload) || (!payload.startsWith(PAYLOAD_PREFIX)
                && !payload.startsWith(WORKER_PAYLOAD_PREFIX)
                && !payload.startsWith(MANAGER_AUDIT_PAYLOAD_PREFIX))) {
            return Optional.empty();
        }

        if (chatId > 0) {
            return Optional.of("Эта ссылка нужна для группы. Добавьте бота в Telegram-группу через кнопку в карточке.");
        }

        if (payload.startsWith(WORKER_PAYLOAD_PREFIX)) {
            return handleWorkerGroupStartCommand(chatId, payload);
        }
        if (payload.startsWith(MANAGER_AUDIT_PAYLOAD_PREFIX)) {
            return handleManagerAuditGroupStartCommand(chatId, payload);
        }

        Long companyId = consumePayload(payload, PAYLOAD_PREFIX, COMPANY_SCOPE).orElse(null);
        if (companyId == null) {
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

    private Optional<String> handleWorkerGroupStartCommand(long chatId, String payload) {
        Long userId = consumePayload(payload, WORKER_PAYLOAD_PREFIX, WORKER_SCOPE).orElse(null);
        if (userId == null) {
            log.warn("Telegram worker group link rejected: invalid payload '{}', chatId={}", payload, chatId);
            return Optional.of("Не удалось привязать группу специалиста: ссылка устарела или неверная.");
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return Optional.of("Не удалось привязать группу специалиста: пользователь не найден.");
        }

        user.setWorkerTelegramGroupChatId(chatId);
        userRepository.save(user);
        log.info("Telegram group chatId={} linked to worker user id={} username='{}'", chatId, user.getId(), user.getUsername());

        String title = hasText(user.getFio()) ? user.getFio().trim() : user.getUsername();
        return Optional.of("Готово: Telegram-группа привязана к специалисту \"" + title + "\".");
    }

    private Optional<String> handleManagerAuditGroupStartCommand(long chatId, String payload) {
        Long managerId = consumePayload(payload, MANAGER_AUDIT_PAYLOAD_PREFIX, MANAGER_AUDIT_SCOPE).orElse(null);
        if (managerId == null) {
            log.warn("Telegram manager audit group link rejected: invalid payload '{}', chatId={}", payload, chatId);
            return Optional.of("Не удалось привязать группу аудита менеджера: ссылка устарела или неверная.");
        }
        Manager manager = managerRepository.findByIdWithUser(managerId).orElse(null);
        if (manager == null || manager.getUser() == null) {
            return Optional.of("Не удалось привязать группу аудита: менеджер не найден.");
        }
        Manager occupied = managerRepository.findByAuditTelegramGroupChatId(chatId).orElse(null);
        if (occupied != null && !occupied.getId().equals(managerId)) {
            return Optional.of("Эта группа уже привязана к аудиту другого менеджера.");
        }
        manager.setAuditTelegramGroupChatId(chatId);
        managerRepository.save(manager);
        String title = hasText(manager.getUser().getFio())
                ? manager.getUser().getFio().trim()
                : manager.getUser().getUsername();
        log.info("Telegram group chatId={} linked to manager audit managerId={} username='{}'",
                chatId, managerId, manager.getUser().getUsername());
        return Optional.of("Готово: Telegram-группа привязана к аудиту менеджера \"" + title + "\".");
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

    private User matchWorkerByPublicUsername(String username, List<User> candidates) {
        if (candidates.size() == 1 && username.equals(telegramPublicUsername(candidates.getFirst().getWorkerChatUrl()).orElse(null))) {
            return candidates.getFirst();
        }

        for (User candidate : candidates) {
            if (username.equals(telegramPublicUsername(candidate.getWorkerChatUrl()).orElse(null))) {
                return candidate;
            }
        }

        return null;
    }

    private String payloadForCompany(Long companyId) {
        return PAYLOAD_PREFIX + tokenStore.issue(COMPANY_SCOPE, companyId, linkSecret);
    }

    private String payloadForWorker(Long userId) {
        return WORKER_PAYLOAD_PREFIX + tokenStore.issue(WORKER_SCOPE, userId, linkSecret);
    }

    private String payloadForManagerAudit(Long managerId) {
        return MANAGER_AUDIT_PAYLOAD_PREFIX + tokenStore.issue(MANAGER_AUDIT_SCOPE, managerId, linkSecret);
    }

    private Optional<Long> consumePayload(String payload, String prefix, String scope) {
        if (!hasText(payload) || !payload.startsWith(prefix) || payload.length() <= prefix.length()) {
            return Optional.empty();
        }
        return tokenStore.consume(payload.substring(prefix.length()), scope, linkSecret);
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
