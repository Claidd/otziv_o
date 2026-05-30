package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class WhatsAppGroupCompanyLinker {

    private static final Pattern WHATSAPP_INVITE_URL = Pattern.compile(
            "(?i)^https?://chat\\.whatsapp\\.com/([A-Za-z0-9_-]{10,})(?:[/?#].*)?$"
    );
    private static final Pattern REVIEWS_SUFFIX = Pattern.compile(
            "(?iu)(?:[\\s.\\-–—]*отзывы\\s*)+$"
    );
    private static final Pattern TRAILING_SERVICE_WORDS = Pattern.compile(
            "(?iu)(?:\\s*(?:отзывы?\\s*всегда|ведение\\s*отзывов|отзывы?|озывы|отзывв|"
                    + "2\\s*гис|2\\s*gis|2\\s*гиз|два\\s*гис|сап\\s*\\d+|ответы|по\\s*счету|счет))+$"
    );
    private static final Pattern NON_TITLE_CHARS = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}\\s]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Set<String> SERVICE_PREFIXES = Set.of(
            "ку", "ви", "нс", "л", "ал", "м", "аб", "н", "ю", "ож", "вн",
            "юф", "ни", "ар", "вр", "ки", "кк", "лу", "мр", "п", "у", "и", "в", "б", "к"
    );

    private final CompanyRepository companyRepository;

    List<Company> companiesWithChatUrl() {
        return companyRepository.findAllWithChatUrl();
    }

    public int linkByInvite(String groupId, String inviteLink) {
        if (!hasText(groupId)) {
            return 0;
        }

        Optional<String> inviteCode = whatsAppInviteCode(inviteLink);
        if (inviteCode.isEmpty()) {
            return 0;
        }

        String code = inviteCode.get();
        List<Company> candidates = companyRepository.findByUrlChatContainingIgnoreCase(code);
        int updated = 0;
        for (Company candidate : candidates) {
            if (!code.equals(whatsAppInviteCode(candidate.getUrlChat()).orElse(null))
                    || Objects.equals(candidate.getGroupId(), groupId)) {
                continue;
            }

            String previousGroupId = candidate.getGroupId();
            candidate.setGroupId(groupId);
            companyRepository.save(candidate);
            updated++;
            if (hasText(previousGroupId)) {
                log.info("WhatsApp groupId for company id={} title='{}' refreshed by invite link: {} -> {}",
                        candidate.getId(), candidate.getTitle(), previousGroupId, groupId);
            } else {
                log.info("WhatsApp groupId={} linked by invite link to company id={} title='{}'",
                        groupId, candidate.getId(), candidate.getTitle());
            }
        }
        return updated;
    }

    public int linkByGroupName(String groupId, String groupName) {
        return linkByGroupName(groupId, groupName, companyRepository.findAllWithChatUrl());
    }

    int linkByGroupName(String groupId, String groupName, List<Company> companies) {
        if (!hasText(groupId) || !hasText(groupName)) {
            return 0;
        }

        List<String> titleParts = groupTitleParts(groupName);
        if (titleParts.isEmpty()) {
            return 0;
        }

        Map<String, List<Company>> companiesByTitle = companiesByNormalizedTitle(companies);
        LinkedHashSet<Company> candidates = new LinkedHashSet<>();
        List<String> missingTitleParts = new ArrayList<>();
        for (String rawTitlePart : titleParts) {
            List<String> titlePartCandidates = groupTitlePartCandidates(rawTitlePart);
            if (titlePartCandidates.isEmpty()) {
                continue;
            }
            List<Company> matches = firstMatches(companiesByTitle, titlePartCandidates);
            if (matches == null || matches.isEmpty()) {
                missingTitleParts.add(normalizeTitle(rawTitlePart) + " candidates=" + titlePartCandidates);
                continue;
            }
            candidates.addAll(matches);
        }

        if (candidates.isEmpty()) {
            log.info(
                    "WhatsApp group name sync skipped groupId={} groupName='{}' reason=title_part_not_found missingTitleParts={}",
                    groupId,
                    groupName,
                    missingTitleParts
            );
            return 0;
        }

        Set<String> chatKeys = new LinkedHashSet<>();
        for (Company candidate : candidates) {
            Optional<String> chatKey = whatsAppInviteCode(candidate.getUrlChat());
            if (chatKey.isEmpty()) {
                log.info(
                        "WhatsApp group name sync skipped groupId={} groupName='{}' reason=matched_company_without_whatsapp_link companyId={} title='{}'",
                        groupId,
                        groupName,
                        candidate.getId(),
                        candidate.getTitle()
                );
                return 0;
            }
            chatKeys.add(chatKey.get());
        }

        if (chatKeys.size() != 1) {
            log.warn(
                    "WhatsApp group name sync skipped groupId={} groupName='{}' reason=matched_companies_have_different_chat_links chatKeys={}",
                    groupId,
                    groupName,
                    chatKeys
            );
            return 0;
        }

        for (Company candidate : candidates) {
            if (hasText(candidate.getGroupId()) && !Objects.equals(candidate.getGroupId(), groupId)) {
                log.warn(
                        "WhatsApp group name sync skipped groupId={} groupName='{}' reason=conflicting_existing_group_id companyId={} title='{}' existing={}",
                        groupId,
                        groupName,
                        candidate.getId(),
                        candidate.getTitle(),
                        candidate.getGroupId()
                );
                return 0;
            }
        }

        int updated = 0;
        for (Company candidate : candidates) {
            if (Objects.equals(candidate.getGroupId(), groupId)) {
                continue;
            }
            candidate.setGroupId(groupId);
            companyRepository.save(candidate);
            updated++;
            log.info(
                    "WhatsApp groupId={} linked by group name '{}' to company id={} title='{}'",
                    groupId,
                    groupName,
                    candidate.getId(),
                    candidate.getTitle()
            );
        }
        if (updated > 0 && !missingTitleParts.isEmpty()) {
            log.info(
                    "WhatsApp group name sync partially linked groupId={} groupName='{}' linkedCompanies={} missingTitleParts={}",
                    groupId,
                    groupName,
                    candidates.size(),
                    missingTitleParts
            );
        }
        return updated;
    }

    static Optional<String> whatsAppInviteCode(String value) {
        if (!hasText(value)) {
            return Optional.empty();
        }

        String trimmed = value.trim();
        if (trimmed.matches("^[A-Za-z0-9_-]{10,}$")) {
            return Optional.of(trimmed.toLowerCase(Locale.ROOT));
        }

        Matcher matcher = WHATSAPP_INVITE_URL.matcher(trimmed);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
    }

    private static Map<String, List<Company>> companiesByNormalizedTitle(List<Company> companies) {
        Map<String, List<Company>> result = new LinkedHashMap<>();
        if (companies == null) {
            return result;
        }

        for (Company company : companies) {
            String title = normalizeTitle(company == null ? null : company.getTitle());
            if (title.isBlank()) {
                continue;
            }
            result.computeIfAbsent(title, ignored -> new ArrayList<>()).add(company);
        }
        return result;
    }

    private static List<String> groupTitleParts(String groupName) {
        String base = REVIEWS_SUFFIX.matcher(groupName.trim()).replaceAll("");
        String[] rawParts = base.split(",");
        List<String> parts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String rawPart : rawParts) {
            String part = rawPart == null ? "" : rawPart.trim();
            if (!part.isBlank() && seen.add(part)) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static List<Company> firstMatches(Map<String, List<Company>> companiesByTitle, List<String> titleCandidates) {
        for (String titleCandidate : titleCandidates) {
            List<Company> matches = companiesByTitle.get(titleCandidate);
            if (matches != null && !matches.isEmpty()) {
                return matches;
            }
        }
        return List.of();
    }

    private static List<String> groupTitlePartCandidates(String rawPart) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String title = normalizeTitle(rawPart);
        addTitleCandidate(candidates, title);
        addTitleCandidate(candidates, withoutLeadingServicePrefixes(title, 1));
        addTitleCandidate(candidates, withoutLeadingServicePrefixes(title, 2));
        return new ArrayList<>(candidates);
    }

    private static void addTitleCandidate(Set<String> candidates, String title) {
        if (hasText(title)) {
            candidates.add(title);
        }
    }

    private static String withoutLeadingServicePrefixes(String title, int maxPrefixes) {
        if (!hasText(title) || maxPrefixes <= 0) {
            return title;
        }

        String[] tokens = SPACES.split(title.trim());
        int removeCount = 0;
        while (removeCount < maxPrefixes
                && removeCount < tokens.length - 1
                && SERVICE_PREFIXES.contains(tokens[removeCount])) {
            removeCount++;
        }

        if (removeCount == 0) {
            return title;
        }

        return String.join(" ", java.util.Arrays.copyOfRange(tokens, removeCount, tokens.length));
    }

    private static String normalizeTitle(String value) {
        if (!hasText(value)) {
            return "";
        }

        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е');
        normalized = REVIEWS_SUFFIX.matcher(normalized).replaceAll("");
        normalized = NON_TITLE_CHARS.matcher(normalized).replaceAll(" ");
        normalized = SPACES.matcher(normalized).replaceAll(" ").trim();
        normalized = TRAILING_SERVICE_WORDS.matcher(normalized).replaceAll("");
        return SPACES.matcher(normalized).replaceAll(" ").trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
