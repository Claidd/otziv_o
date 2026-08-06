package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.services.CompanyChatBindingPolicy;
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
            "(?i)^(?:https?://)?chat\\.whatsapp\\.com/([A-Za-z0-9_-]{10,})(?:[/?#].*)?$"
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
    private static final Pattern NUMBER_MARKER = Pattern.compile("(?iu)\\b(?:no|n)\\s+(?=\\d)");
    private static final Pattern LEADING_ZERO_NUMBER = Pattern.compile("\\b0+(\\d+)\\b");
    private static final Pattern LETTER_DIGIT_BOUNDARY = Pattern.compile("(?U)(?<=\\p{Alpha})(?=\\p{Digit})|(?<=\\p{Digit})(?=\\p{Alpha})");
    private static final Set<String> SERVICE_PREFIXES = Set.of(
            "ку", "ви", "нс", "л", "ал", "м", "аб", "н", "ю", "ож", "вн",
            "юф", "ни", "ар", "вр", "ки", "кк", "лу", "мр", "п", "у", "и", "в", "б", "к"
    );

    private final CompanyRepository companyRepository;

    List<Company> companiesWithChatUrl() {
        return companyRepository.findAllWithChatUrl();
    }

    public int linkByInvite(String groupId, String inviteLink) {
        return linkByInvite(groupId, inviteLink, null);
    }

    int linkByInvite(String groupId, String inviteLink, List<Company> companiesWithChatUrl) {
        if (!hasText(groupId)) {
            return 0;
        }

        Optional<String> inviteCode = whatsAppInviteCode(inviteLink);
        if (inviteCode.isEmpty()) {
            return 0;
        }

        String code = inviteCode.get();
        List<Company> candidates = companiesWithChatUrl == null
                ? companyRepository.findByUrlChatContainingIgnoreCase(code.toLowerCase(Locale.ROOT))
                : companiesWithSameInviteCode(code, companiesWithChatUrl);
        int updated = 0;
        for (Company candidate : candidates) {
            if (!CompanyChatBindingPolicy.isRequired(candidate)
                    || !code.equals(whatsAppInviteCode(candidate.getUrlChat()).orElse(null))
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

    private List<Company> companiesWithSameInviteCode(String code, List<Company> companies) {
        if (!hasText(code) || companies == null || companies.isEmpty()) {
            return List.of();
        }

        List<Company> result = new ArrayList<>();
        for (Company company : companies) {
            if (CompanyChatBindingPolicy.isRequired(company)
                    && code.equals(whatsAppInviteCode(company.getUrlChat()).orElse(null))) {
                result.add(company);
            }
        }
        return result;
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
            return Optional.of(trimmed);
        }

        Matcher matcher = WHATSAPP_INVITE_URL.matcher(trimmed);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        return Optional.of(matcher.group(1));
    }

    int refreshRotatedInviteLink(
            String groupId,
            String groupName,
            String activeInviteLink,
            List<Company> companies
    ) {
        Optional<String> activeCode = whatsAppInviteCode(activeInviteLink);
        if (!hasText(groupId) || !hasText(groupName) || activeCode.isEmpty()
                || companies == null || companies.isEmpty()) {
            return 0;
        }

        String normalizedGroupName = normalizeTitle(groupName);
        if (normalizedGroupName.isBlank()) {
            return 0;
        }

        List<Company> targets = new ArrayList<>();
        for (Company company : companies) {
            // Invite rotation only refreshes metadata; the confirmed groupId is never changed.
            if (!Objects.equals(groupId, company.getGroupId())
                    || activeCode.get().equals(whatsAppInviteCode(company.getUrlChat()).orElse(null))) {
                continue;
            }

            String companyTitle = normalizeTitle(company.getTitle());
            if (!isContainedTitleMatch(normalizedGroupName, companyTitle)) {
                log.warn(
                        "WhatsApp invite rotation skipped groupId={} groupName='{}' reason=company_title_not_confirmed companyId={} title='{}'",
                        groupId,
                        groupName,
                        company.getId(),
                        company.getTitle()
                );
                return 0;
            }
            targets.add(company);
        }

        String canonicalLink = "https://chat.whatsapp.com/" + activeCode.get();
        for (Company target : targets) {
            String previousLink = target.getUrlChat();
            target.setUrlChat(canonicalLink);
            companyRepository.save(target);
            log.info(
                    "WhatsApp invite link for company id={} title='{}' refreshed after rotation: {} -> {}",
                    target.getId(),
                    target.getTitle(),
                    previousLink,
                    canonicalLink
            );
        }
        return targets.size();
    }

    private static Map<String, List<Company>> companiesByNormalizedTitle(List<Company> companies) {
        Map<String, List<Company>> result = new LinkedHashMap<>();
        if (companies == null) {
            return result;
        }

        for (Company company : companies) {
            if (!CompanyChatBindingPolicy.isRequired(company)) {
                continue;
            }
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
        for (String titleCandidate : titleCandidates) {
            List<Company> matches = uniquePrefixMatches(companiesByTitle, titleCandidate);
            if (!matches.isEmpty()) {
                return matches;
            }
        }
        for (String titleCandidate : titleCandidates) {
            List<Company> matches = uniqueContainedTitleMatches(companiesByTitle, titleCandidate);
            if (!matches.isEmpty()) {
                return matches;
            }
        }
        for (String titleCandidate : titleCandidates) {
            List<Company> matches = uniqueFuzzyMatches(companiesByTitle, titleCandidate);
            if (!matches.isEmpty()) {
                return matches;
            }
        }
        return List.of();
    }

    private static List<Company> uniquePrefixMatches(Map<String, List<Company>> companiesByTitle, String titleCandidate) {
        if (!hasText(titleCandidate)) {
            return List.of();
        }

        List<Company> result = new ArrayList<>();
        String prefix = titleCandidate + " ";
        for (Map.Entry<String, List<Company>> entry : companiesByTitle.entrySet()) {
            String companyTitle = entry.getKey();
            if (!companyTitle.startsWith(prefix)) {
                continue;
            }
            result.addAll(entry.getValue());
            if (result.size() > 1) {
                return List.of();
            }
        }
        return result.size() == 1 ? result : List.of();
    }

    private static List<Company> uniqueContainedTitleMatches(
            Map<String, List<Company>> companiesByTitle,
            String titleCandidate
    ) {
        if (!hasText(titleCandidate)) {
            return List.of();
        }

        List<Company> result = new ArrayList<>();
        for (Map.Entry<String, List<Company>> entry : companiesByTitle.entrySet()) {
            String companyTitle = entry.getKey();
            if (!isContainedTitleMatch(titleCandidate, companyTitle)) {
                continue;
            }
            result.addAll(entry.getValue());
            if (result.size() > 1) {
                return List.of();
            }
        }
        return result.size() == 1 ? result : List.of();
    }

    private static boolean isContainedTitleMatch(String groupTitle, String companyTitle) {
        if (!hasText(groupTitle) || !hasText(companyTitle)) {
            return false;
        }
        if (companyTitle.length() < 4) {
            return false;
        }
        return (" " + groupTitle + " ").contains(" " + companyTitle + " ");
    }

    private static List<Company> uniqueFuzzyMatches(Map<String, List<Company>> companiesByTitle, String titleCandidate) {
        if (!hasText(titleCandidate) || titleCandidate.length() < 6) {
            return List.of();
        }

        List<Company> result = new ArrayList<>();
        for (Map.Entry<String, List<Company>> entry : companiesByTitle.entrySet()) {
            String companyTitle = entry.getKey();
            if (!isSmallTypo(titleCandidate, companyTitle)) {
                continue;
            }
            result.addAll(entry.getValue());
            if (result.size() > 1) {
                return List.of();
            }
        }
        return result.size() == 1 ? result : List.of();
    }

    private static boolean isSmallTypo(String first, String second) {
        if (!hasText(first) || !hasText(second)) {
            return false;
        }
        int maxLength = Math.max(first.length(), second.length());
        if (maxLength < 6 || Math.abs(first.length() - second.length()) > 1) {
            return false;
        }
        return levenshteinAtMost(first, second, maxLength >= 10 ? 2 : 1);
    }

    private static boolean levenshteinAtMost(String first, String second, int threshold) {
        int[] previous = new int[second.length() + 1];
        int[] current = new int[second.length() + 1];
        for (int j = 0; j <= second.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= first.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= second.length(); j++) {
                int cost = first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost
                );
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > threshold) {
                return false;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[second.length()] <= threshold;
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
        normalized = LETTER_DIGIT_BOUNDARY.matcher(normalized).replaceAll(" ");
        normalized = SPACES.matcher(normalized).replaceAll(" ").trim();
        normalized = TRAILING_SERVICE_WORDS.matcher(normalized).replaceAll("");
        normalized = NUMBER_MARKER.matcher(normalized).replaceAll("");
        normalized = normalizeNumberTokens(normalized);
        return SPACES.matcher(normalized).replaceAll(" ").trim();
    }

    private static String normalizeNumberTokens(String value) {
        Matcher matcher = LEADING_ZERO_NUMBER.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(1)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
