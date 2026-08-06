package com.hunt.otziv.c_companies.services;

import com.hunt.otziv.c_companies.dto.SharedChatLinkSyncResponse;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class SharedChatLinkSyncService {

    private static final Pattern WHATSAPP_INVITE_URL = Pattern.compile(
            "(?i)^(?:https?://)?chat\\.whatsapp\\.com/([A-Za-z0-9_-]{10,})(?:[/?#].*)?$"
    );
    private static final Pattern TELEGRAM_CHAT_URL = Pattern.compile(
            "(?i)^(?:https?://)?(?:t\\.me|telegram\\.me|telegram\\.dog)/([+@]?)([A-Za-z0-9_-]{5,32})(?:[/?#].*)?$"
    );
    private static final Pattern TELEGRAM_RESOLVE_URL = Pattern.compile(
            "(?i)^tg://resolve\\?(?:.*&)?domain=([A-Za-z0-9_]{5,32})(?:&.*)?$"
    );
    private static final Pattern MAX_WEB_CHAT_URL = Pattern.compile(
            "(?i)^(?:https?://)?web\\.max\\.ru/(-?\\d{5,})(?:[/?#].*)?$"
    );
    private static final Pattern MAX_JOIN_URL = Pattern.compile(
            "(?i)^(?:https?://)?(?:web\\.)?max\\.ru/join/([A-Za-z0-9_-]{8,})(?:[/?#].*)?$"
    );

    private final CompanyRepository companyRepository;

    @Transactional
    public SharedChatLinkSyncResponse syncSharedChatIds() {
        List<Company> companies = companyRepository.findAllWithChatUrl().stream()
                .filter(CompanyChatBindingPolicy::isRequired)
                .toList();
        Map<String, List<Company>> companiesByChat = new LinkedHashMap<>();
        for (Company company : companies) {
            normalizedChatKey(company.getUrlChat())
                    .ifPresent(key -> companiesByChat.computeIfAbsent(key, ignored -> new ArrayList<>()).add(company));
        }

        SyncTotals totals = new SyncTotals(companies.size());
        Set<Company> updatedCompanies = new LinkedHashSet<>();
        for (Map.Entry<String, List<Company>> entry : companiesByChat.entrySet()) {
            List<Company> group = entry.getValue();
            if (group.size() < 2) {
                continue;
            }

            totals.sharedChatGroups++;
            ChatPlatform platform = platformFromChatKey(entry.getKey());
            switch (platform) {
                case WHATSAPP -> {
                    syncPlatform(
                            "WhatsApp",
                            entry.getKey(),
                            group,
                            company -> normalizeNullable(company.getGroupId()),
                            (company, value) -> company.setGroupId(value),
                            updatedCompanies,
                            totals::addWhatsApp
                    );
                    totals.conflictGroups += conflictCount(group, platform);
                }
                case TELEGRAM -> {
                    syncPlatform(
                            "Telegram",
                            entry.getKey(),
                            group,
                            company -> company.getTelegramGroupChatId() == null ? null : String.valueOf(company.getTelegramGroupChatId()),
                            (company, value) -> company.setTelegramGroupChatId(Long.valueOf(value)),
                            updatedCompanies,
                            totals::addTelegram
                    );
                    totals.conflictGroups += conflictCount(group, platform);
                }
                case MAX -> {
                    syncPlatform(
                            "MAX",
                            entry.getKey(),
                            group,
                            company -> company.getMaxGroupChatId() == null ? null : String.valueOf(company.getMaxGroupChatId()),
                            SharedChatLinkSyncService::writeMaxGroupChatId,
                            updatedCompanies,
                            totals::addMax
                    );
                    totals.conflictGroups += conflictCount(group, platform);
                }
                case UNKNOWN -> log.warn("Shared chat link sync skipped chatKey={} because messenger type is unknown", entry.getKey());
            }
        }

        if (!updatedCompanies.isEmpty()) {
            companyRepository.saveAll(updatedCompanies);
        }

        SharedChatLinkSyncResponse response = new SharedChatLinkSyncResponse(
                totals.scannedCompanies,
                totals.sharedChatGroups,
                updatedCompanies.size(),
                totals.whatsappLinked,
                totals.telegramLinked,
                totals.maxLinked,
                totals.conflictGroups
        );
        log.info(
                "Shared chat link sync finished: scannedCompanies={} sharedChatGroups={} updatedCompanies={} whatsappLinked={} telegramLinked={} maxLinked={} conflictGroups={}",
                response.scannedCompanies(),
                response.sharedChatGroups(),
                response.updatedCompanies(),
                response.whatsappLinked(),
                response.telegramLinked(),
                response.maxLinked(),
                response.conflictGroups()
        );
        return response;
    }

    @Transactional
    public SharedChatLinkSyncResponse syncCompanyChatId(Long companyId) {
        return syncCompanyChatId(companyId, null);
    }

    @Transactional
    public SharedChatLinkSyncResponse syncCompanyChatId(Long companyId, String authoritativeChatId) {
        if (companyId == null) {
            return emptyResponse();
        }

        Company target = companyRepository.findById(companyId).orElse(null);
        if (target == null) {
            return emptyResponse();
        }

        Optional<String> targetKey = normalizedChatKey(target.getUrlChat());
        if (targetKey.isEmpty()) {
            return emptyResponse();
        }

        List<Company> companies = companyRepository.findAllWithChatUrl();
        List<Company> group = companies.stream()
                .filter(company -> targetKey.get().equals(normalizedChatKey(company.getUrlChat()).orElse(null)))
                .toList();
        if (group.isEmpty()) {
            group = List.of(target);
        }

        SyncTotals totals = new SyncTotals(companies.size());
        if (group.size() > 1) {
            totals.sharedChatGroups++;
        }

        Set<Company> updatedCompanies = new LinkedHashSet<>();
        ChatPlatform platform = platformFromChatKey(targetKey.get());
        String authoritativeId = normalizeNullable(authoritativeChatId);
        switch (platform) {
            case WHATSAPP -> syncPlatformTargeted(
                    "WhatsApp",
                    targetKey.get(),
                    group,
                    companyId,
                    authoritativeId,
                    company -> normalizeNullable(company.getGroupId()),
                    (company, value) -> company.setGroupId(value),
                    updatedCompanies,
                    totals::addWhatsApp
            );
            case TELEGRAM -> syncPlatformTargeted(
                    "Telegram",
                    targetKey.get(),
                    group,
                    companyId,
                    authoritativeId,
                    company -> company.getTelegramGroupChatId() == null ? null : String.valueOf(company.getTelegramGroupChatId()),
                    (company, value) -> company.setTelegramGroupChatId(Long.valueOf(value)),
                    updatedCompanies,
                    totals::addTelegram
            );
            case MAX -> syncPlatformTargeted(
                    "MAX",
                    targetKey.get(),
                    group,
                    companyId,
                    firstNonBlank(authoritativeId, directMaxWebChatId(targetKey.get())),
                    company -> company.getMaxGroupChatId() == null ? null : String.valueOf(company.getMaxGroupChatId()),
                    SharedChatLinkSyncService::writeMaxGroupChatId,
                    updatedCompanies,
                    totals::addMax
            );
            case UNKNOWN -> log.warn("Targeted chat link sync skipped companyId={} chatKey={} because messenger type is unknown",
                    companyId, targetKey.get());
        }

        if (!updatedCompanies.isEmpty()) {
            companyRepository.saveAll(updatedCompanies);
        }

        SharedChatLinkSyncResponse response = new SharedChatLinkSyncResponse(
                totals.scannedCompanies,
                totals.sharedChatGroups,
                updatedCompanies.size(),
                totals.whatsappLinked,
                totals.telegramLinked,
                totals.maxLinked,
                totals.conflictGroups
        );
        log.info(
                "Targeted chat link sync finished companyId={} chatKey={} scannedCompanies={} sharedChatGroups={} updatedCompanies={} whatsappLinked={} telegramLinked={} maxLinked={} conflictGroups={}",
                companyId,
                targetKey.get(),
                response.scannedCompanies(),
                response.sharedChatGroups(),
                response.updatedCompanies(),
                response.whatsappLinked(),
                response.telegramLinked(),
                response.maxLinked(),
                response.conflictGroups()
        );
        return response;
    }

    private void syncPlatform(
            String platform,
            String chatKey,
            List<Company> companies,
            ChatIdReader reader,
            ChatIdWriter writer,
            Set<Company> updatedCompanies,
            Counter counter
    ) {
        Set<String> knownIds = new LinkedHashSet<>();
        for (Company company : companies) {
            String value = normalizeNullable(reader.read(company));
            if (value != null) {
                knownIds.add(value);
            }
        }

        if (knownIds.isEmpty()) {
            return;
        }
        if (knownIds.size() > 1) {
            log.warn("Shared chat link sync skipped {} for chatKey={} because companies have conflicting ids: {}",
                    platform, chatKey, knownIds);
            return;
        }

        String sharedId = knownIds.iterator().next();
        int linked = 0;
        for (Company company : companies) {
            if (normalizeNullable(reader.read(company)) != null) {
                continue;
            }

            writer.write(company, sharedId);
            updatedCompanies.add(company);
            linked++;
        }

        if (linked > 0) {
            counter.add(linked);
            log.info("Shared chat link sync copied {} id={} to {} company(s) for chatKey={}",
                    platform, sharedId, linked, chatKey);
        }
    }

    private void syncPlatformTargeted(
            String platform,
            String chatKey,
            List<Company> companies,
            Long targetCompanyId,
            String authoritativeId,
            ChatIdReader reader,
            ChatIdWriter writer,
            Set<Company> updatedCompanies,
            Counter counter
    ) {
        Set<String> knownIds = new LinkedHashSet<>();
        boolean hasAuthoritativeId = normalizeNullable(authoritativeId) != null;
        if (hasAuthoritativeId) {
            knownIds.add(authoritativeId.trim());
        }
        for (Company company : companies) {
            if (!hasAuthoritativeId && Objects.equals(company.getId(), targetCompanyId)) {
                continue;
            }
            String value = normalizeNullable(reader.read(company));
            if (value != null) {
                knownIds.add(value);
            }
        }

        if (knownIds.isEmpty()) {
            return;
        }
        if (knownIds.size() > 1 && normalizeNullable(authoritativeId) == null) {
            log.warn("Targeted chat link sync skipped {} for chatKey={} because companies have conflicting ids: {}",
                    platform, chatKey, knownIds);
            return;
        }

        String sharedId = normalizeNullable(authoritativeId) != null
                ? authoritativeId.trim()
                : knownIds.iterator().next();
        int linked = 0;
        for (Company company : companies) {
            if (!isTargetedSyncTarget(company, targetCompanyId)) {
                continue;
            }

            String current = normalizeNullable(reader.read(company));
            if (Objects.equals(current, sharedId)) {
                continue;
            }

            writer.write(company, sharedId);
            updatedCompanies.add(company);
            linked++;
        }

        if (linked > 0) {
            counter.add(linked);
            log.info("Targeted chat link sync wrote {} id={} to {} company(s) for chatKey={}",
                    platform, sharedId, linked, chatKey);
        }
    }

    private int conflictCount(List<Company> companies, ChatPlatform platform) {
        Set<String> values = switch (platform) {
            case WHATSAPP -> distinctValues(companies, company -> normalizeNullable(company.getGroupId()));
            case TELEGRAM -> distinctValues(companies, company -> company.getTelegramGroupChatId() == null ? null : String.valueOf(company.getTelegramGroupChatId()));
            case MAX -> distinctValues(companies, company -> company.getMaxGroupChatId() == null ? null : String.valueOf(company.getMaxGroupChatId()));
            case UNKNOWN -> Set.of();
        };
        return values.size() > 1 ? 1 : 0;
    }

    private Set<String> distinctValues(List<Company> companies, ChatIdReader reader) {
        Set<String> values = new LinkedHashSet<>();
        for (Company company : companies) {
            String value = normalizeNullable(reader.read(company));
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    static Optional<String> normalizedChatKey(String value) {
        if (!hasText(value)) {
            return Optional.empty();
        }

        String trimmed = value.trim();
        if (isTelegramBotStartGroupLink(trimmed)) {
            return Optional.empty();
        }

        Matcher whatsAppMatcher = WHATSAPP_INVITE_URL.matcher(trimmed);
        if (whatsAppMatcher.matches()) {
            return Optional.of("whatsapp:" + whatsAppMatcher.group(1));
        }

        Matcher telegramMatcher = TELEGRAM_CHAT_URL.matcher(trimmed);
        if (telegramMatcher.matches()) {
            String prefix = "+".equals(telegramMatcher.group(1)) ? "invite:" : "public:";
            return Optional.of("telegram:" + prefix + telegramMatcher.group(2).toLowerCase(Locale.ROOT));
        }

        Matcher telegramResolveMatcher = TELEGRAM_RESOLVE_URL.matcher(trimmed);
        if (telegramResolveMatcher.matches()) {
            return Optional.of("telegram:public:" + telegramResolveMatcher.group(1).toLowerCase(Locale.ROOT));
        }

        Matcher maxWebMatcher = MAX_WEB_CHAT_URL.matcher(trimmed);
        if (maxWebMatcher.matches()) {
            return Optional.of("max:web:" + maxWebMatcher.group(1));
        }

        Matcher maxJoinMatcher = MAX_JOIN_URL.matcher(trimmed);
        if (maxJoinMatcher.matches()) {
            return Optional.of("max:join:" + maxJoinMatcher.group(1).toLowerCase(Locale.ROOT));
        }

        return Optional.of("url:" + normalizeUrlFallback(trimmed));
    }

    private static ChatPlatform platformFromChatKey(String chatKey) {
        if (chatKey == null) {
            return ChatPlatform.UNKNOWN;
        }
        if (chatKey.startsWith("whatsapp:")) {
            return ChatPlatform.WHATSAPP;
        }
        if (chatKey.startsWith("telegram:")) {
            return ChatPlatform.TELEGRAM;
        }
        if (chatKey.startsWith("max:")) {
            return ChatPlatform.MAX;
        }
        return ChatPlatform.UNKNOWN;
    }

    private static String normalizeUrlFallback(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int queryIndex = firstNonNegative(normalized.indexOf('?'), normalized.indexOf('#'));
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        return normalized.replaceAll("/+$", "");
    }

    private static int firstNonNegative(int first, int second) {
        if (first < 0) {
            return second;
        }
        if (second < 0) {
            return first;
        }
        return Math.min(first, second);
    }

    private static String normalizeNullable(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        String normalizedFirst = normalizeNullable(first);
        return normalizedFirst != null ? normalizedFirst : normalizeNullable(second);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean isTelegramBotStartGroupLink(String value) {
        return hasText(value) && value.toLowerCase(Locale.ROOT).contains("startgroup=");
    }

    private static boolean isTargetedSyncTarget(Company company, Long targetCompanyId) {
        return company != null
                && (Objects.equals(company.getId(), targetCompanyId) || CompanyChatBindingPolicy.isRequired(company));
    }

    private static String directMaxWebChatId(String chatKey) {
        String prefix = "max:web:";
        if (!hasText(chatKey) || !chatKey.startsWith(prefix)) {
            return null;
        }
        return chatKey.substring(prefix.length());
    }

    private static void writeMaxGroupChatId(Company company, String value) {
        company.setMaxGroupChatId(Long.valueOf(value));
        company.setMaxLinkUserId(null);
        company.setMaxLinkRequestedAt(null);
    }

    private static SharedChatLinkSyncResponse emptyResponse() {
        return new SharedChatLinkSyncResponse(0, 0, 0, 0, 0, 0, 0);
    }

    private interface ChatIdReader {
        String read(Company company);
    }

    private interface ChatIdWriter {
        void write(Company company, String value);
    }

    private interface Counter {
        void add(int value);
    }

    private static class SyncTotals {
        private final int scannedCompanies;
        private int sharedChatGroups;
        private int whatsappLinked;
        private int telegramLinked;
        private int maxLinked;
        private int conflictGroups;

        private SyncTotals(int scannedCompanies) {
            this.scannedCompanies = scannedCompanies;
        }

        private void addWhatsApp(int value) {
            whatsappLinked += value;
        }

        private void addTelegram(int value) {
            telegramLinked += value;
        }

        private void addMax(int value) {
            maxLinked += value;
        }
    }

    private enum ChatPlatform {
        WHATSAPP,
        TELEGRAM,
        MAX,
        UNKNOWN
    }
}
