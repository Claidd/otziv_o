package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.api.dto.ReputationContentPackRequest;
import com.hunt.otziv.reputationai.domain.CompanyAiProfile;
import com.hunt.otziv.reputationai.domain.CompanySource;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import com.hunt.otziv.reputationai.infrastructure.ai.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.OpenAiProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@Slf4j
@RequiredArgsConstructor
public class AiReputationContentFactory {

    private static final Set<String> GENERIC_QUALITY_WORDS = Set.of(
            "компания", "компании", "клиент", "клиента", "клиенты", "отзывы", "отзывов",
            "рейтинг", "выбрать", "сравнить", "описание", "условия", "можно", "нужно",
            "сервис", "услуги", "товары", "городе", "источник", "search", "website",
            "квест", "квесты"
    );
    private static final int MAX_AD_TEXTS_PER_PACK = 8;
    private static final int MAX_SOCIAL_POSTS_PER_PACK = 5;
    private static final int MAX_POSITIVE_REPLIES_PER_PACK = 8;
    private static final int MAX_NEGATIVE_REPLIES_PER_PACK = 6;

    private final OpenAiProvider openAiProvider;
    private final ObjectMapper objectMapper;
    private final ReputationAiPromptService promptService;

    public Optional<ReputationContentPack> create(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request
    ) {
        if (!openAiProvider.isAvailable()) {
            return Optional.empty();
        }

        try {
            String prompt = userPrompt(snapshot, deepReport, request);
            AiResponse response = openAiProvider.generateContentPack(new AiRequest(
                    "reputation-content-pack",
                    systemPrompt(),
                    prompt,
                    0.25,
                    true
            ), request.contentPackProfile());

            if (response.text().isBlank()) {
                throwIfAiError(response);
                return Optional.empty();
            }

            ParsedPack parsed = parsePackOrRetryCompact(snapshot, deepReport, request, response.text(), "основной ответ");
            ReputationContentPack pack = parsed.pack();
            if (pack.utp().isEmpty() && pack.adTexts().isEmpty() && pack.reviewDraftTemplates().isEmpty()) {
                return Optional.empty();
            }

            ReputationContentPackRequest qualityRequest = parsed.compact() ? compactRequest(request) : request;
            List<String> qualityIssues = qualityIssues(pack, snapshot, deepReport, qualityRequest);
            if (!qualityIssues.isEmpty()) {
                log.warn("AI content pack quality retry: {}", String.join("; ", qualityIssues));
                Optional<ReputationContentPack> revised = reviseLowQualityPack(snapshot, deepReport, request, response.text(), qualityIssues);
                if (revised.isPresent()) {
                    return revised;
                }
                if (!hasBlockingQualityIssues(qualityIssues)) {
                    return Optional.of(packWithQualityWarnings(pack, qualityIssues));
                }
                return Optional.empty();
            }

            return Optional.of(pack);
        } catch (Exception exception) {
            log.warn("AI content pack generation failed: {}", exception.getMessage());
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            return Optional.empty();
        }
    }

    private void throwIfAiError(AiResponse response) {
        if (response != null && !response.errorMessage().isBlank()) {
            throw new IllegalStateException(response.errorMessage());
        }
    }

    private ParsedPack parsePackOrRetryCompact(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request,
            String responseText,
            String stage
    ) throws Exception {
        try {
            return new ParsedPack(parsePack(snapshot, responseText), false);
        } catch (Exception exception) {
            log.warn("AI content pack {} parse failed, retrying compact response: {}", stage, exception.getMessage());
            Optional<ReputationContentPack> compact = retryCompactPack(snapshot, deepReport, request, responseText, exception.getMessage());
            if (compact.isPresent()) {
                return new ParsedPack(compact.get(), true);
            }
            throw exception;
        }
    }

    private Optional<ReputationContentPack> retryCompactPack(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request,
            String previousResponse,
            String parseError
    ) {
        try {
            ReputationContentPackRequest compactRequest = compactRequest(request);
            AiResponse response = openAiProvider.generateContentPack(new AiRequest(
                    "reputation-content-pack-compact-retry",
                    compactSystemPrompt(),
                    compactRetryPrompt(snapshot, deepReport, compactRequest, previousResponse, parseError),
                    0.12,
                    true
            ), compactRequest.contentPackProfile());
            if (response.text().isBlank()) {
                throwIfAiError(response);
                return Optional.empty();
            }
            return Optional.of(parsePack(snapshot, response.text()));
        } catch (Exception exception) {
            log.warn("AI content pack compact retry failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ReputationContentPack> reviseLowQualityPack(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request,
            String previousResponse,
            List<String> qualityIssues
    ) {
        try {
            AiResponse response = openAiProvider.generateContentPack(new AiRequest(
                    "reputation-content-pack-revision",
                    systemPrompt(),
                    revisionPrompt(snapshot, deepReport, request, previousResponse, qualityIssues),
                    0.15,
                    true
            ), request.contentPackProfile());
            if (response.text().isBlank()) {
                throwIfAiError(response);
                return Optional.empty();
            }

            ParsedPack parsed = parsePackOrRetryCompact(snapshot, deepReport, request, response.text(), "revision");
            ReputationContentPack pack = parsed.pack();
            ReputationContentPackRequest qualityRequest = parsed.compact() ? compactRequest(request) : request;
            List<String> revisedIssues = qualityIssues(pack, snapshot, deepReport, qualityRequest);
            if (!revisedIssues.isEmpty()) {
                log.warn("AI content pack revision still low quality: {}", String.join("; ", revisedIssues));
                if (!hasBlockingQualityIssues(revisedIssues)) {
                    return Optional.of(packWithQualityWarnings(pack, revisedIssues));
                }
                return Optional.empty();
            }
            return Optional.of(pack);
        } catch (Exception exception) {
            log.warn("AI content pack revision failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private String revisionPrompt(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request,
            String previousResponse,
            List<String> qualityIssues
    ) throws Exception {
        return renderPromptTemplate(promptService.content(ReputationAiPromptKeys.CONTENT_PACK_REVISION), Map.of(
                "qualityIssues", String.join("; ", qualityIssues),
                "userPrompt", userPrompt(snapshot, deepReport, request),
                "previousResponse", limit(previousResponse, 6000)
        ));
    }

    private boolean hasBlockingQualityIssues(List<String> issues) {
        return issues.stream().anyMatch(this::isBlockingQualityIssue);
    }

    private boolean isBlockingQualityIssue(String issue) {
        return "не хватает обязательных блоков".equals(issue)
                || "мало полноценных постов/статей".equals(issue)
                || "рекламные тексты похожи на анкету с техническими заголовками".equals(issue)
                || "рекламные тексты говорят от лица источников, а не компании".equals(issue)
                || "остались шаблонные инструкции вместо готового текста".equals(issue)
                || "не используются базовые данные компании".equals(issue)
                || "не используются факты из публичных источников".equals(issue)
                || "не используются ключевые факты из глубокого отчета".equals(issue);
    }

    private ReputationContentPack packWithQualityWarnings(ReputationContentPack pack, List<String> issues) {
        List<String> notes = new ArrayList<>(pack.safetyNotes());
        issues.stream()
                .map(issue -> "Редакторская проверка AI-пакета: " + issue + ".")
                .filter(note -> !notes.contains(note))
                .forEach(notes::add);
        return new ReputationContentPack(
                pack.researchSnapshot(),
                pack.companyProfile(),
                pack.utp(),
                pack.adTexts(),
                pack.socialPostTopics(),
                pack.socialPosts(),
                pack.honestReviewTopics(),
                pack.reviewDraftTemplates(),
                pack.positiveReviewReplies(),
                pack.negativeReviewReplies(),
                pack.sourceUrls(),
                notes
        );
    }

    private String systemPrompt() {
        return renderPromptTemplate(promptService.content(ReputationAiPromptKeys.CONTENT_PACK_SYSTEM), Map.of(
                "adTextRange", "350-700"
        ));
    }

    private String compactSystemPrompt() {
        return promptService.content(ReputationAiPromptKeys.CONTENT_PACK_COMPACT_SYSTEM);
    }

    private String userPrompt(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request
    ) throws Exception {
        boolean economy = isEconomyProfile(request);
        String utpRange = economy ? "5-7" : "7-10";
        String adTextRange = economy ? "280-500" : "350-700";
        String socialPostRange = economy ? "650-1000" : "900-1600";
        String reviewDraftRange = economy ? "350-650" : "500-900";
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> company = new LinkedHashMap<>();
        company.put("id", snapshot.companyId());
        company.put("name", snapshot.companyName());
        company.put("city", snapshot.city());
        company.put("website", snapshot.website());
        company.put("category", snapshot.category());
        company.put("subCategory", snapshot.subCategory());
        company.put("notes", snapshot.companyNotes());
        company.put("products", snapshot.products());
        company.put("advantages", snapshot.advantages());
        company.put("positiveTopics", snapshot.commonPositiveTopics());
        company.put("negativeTopics", snapshot.commonNegativeTopics());
        company.put("warnings", snapshot.warnings());
        payload.put("company", company);
        payload.put("search", Map.of(
                "provider", snapshot.searchProvider(),
                "available", snapshot.searchAvailable(),
                "queries", snapshot.searchQueries(),
                "resultsCount", snapshot.searchResultsCount(),
                "websitePagesRead", snapshot.websitePagesRead()
        ));
        payload.put("sources", snapshot.sources().stream()
                .filter(source -> !isLowTrustFactSource(source))
                .limit(12)
                .map(source -> Map.of(
                        "type", source.type(),
                        "title", source.title(),
                        "url", source.url(),
                        "excerpt", limit(source.excerpt(), 1200)
                ))
                .toList());
        payload.put("deepResearch", deepResearchPayload(deepReport));
        payload.put("priorityUrls", priorityUrls(deepReport, request));
        payload.put("evidenceFacts", extractEvidenceFacts(snapshot));
        payload.put("request", Map.of(
                "productOrService", valueOrBlank(request.productOrService()),
                "manualDescription", valueOrBlank(request.manualDescription()),
                "productsOrServices", request.productsOrServices() == null ? List.of() : request.productsOrServices(),
                "publicUrls", request.publicUrls() == null ? List.of() : request.publicUrls(),
                "adTextsCount", boundedCount(request.adTextsCount(), 6, MAX_AD_TEXTS_PER_PACK),
                "socialPostsCount", boundedCount(request.socialPostsCount(), 4, MAX_SOCIAL_POSTS_PER_PACK),
                "positiveReplyCount", boundedCount(request.positiveReplyCount(), 6, MAX_POSITIVE_REPLIES_PER_PACK),
                "negativeReplyCount", boundedCount(request.negativeReplyCount(), 4, MAX_NEGATIVE_REPLIES_PER_PACK)
        ));
        payload.put("jsonSchema", Map.of(
                "companyProfile", Map.of(
                        "shortDescription", "string",
                        "category", "string",
                        "products", "string[]",
                        "advantages", "string[]",
                        "positiveReviewTopics", "string[]",
                        "negativeReviewTopics", "string[]",
                        "factualWarnings", "string[]"
                ),
                "utp", "string[]",
                "adTexts", "string[]",
                "socialPostTopics", "string[]",
                "socialPosts", "string[]",
                "honestReviewTopics", "string[]",
                "reviewDraftTemplates", "string[]",
                "positiveReviewReplies", "string[]",
                "negativeReviewReplies", "string[]",
                "safetyNotes", "string[]"
        ));

        return renderPromptTemplate(promptService.content(ReputationAiPromptKeys.CONTENT_PACK_USER), Map.of(
                "utpRange", utpRange,
                "adTextRange", adTextRange,
                "socialPostRange", socialPostRange,
                "reviewDraftRange", reviewDraftRange,
                "payloadJson", objectMapper.writeValueAsString(payload)
        ));
    }

    private String compactRetryPrompt(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request,
            String previousResponse,
            String parseError
    ) throws Exception {
        return renderPromptTemplate(promptService.content(ReputationAiPromptKeys.CONTENT_PACK_COMPACT_RETRY), Map.of(
                "parseError", valueOrBlank(parseError),
                "adTextsCount", String.valueOf(boundedCount(request.adTextsCount(), 4, 4)),
                "socialPostsCount", String.valueOf(boundedCount(request.socialPostsCount(), 2, 2)),
                "positiveReplyCount", String.valueOf(boundedCount(request.positiveReplyCount(), 4, 4)),
                "negativeReplyCount", String.valueOf(boundedCount(request.negativeReplyCount(), 2, 2)),
                "previousResponse", limit(previousResponse, 1800),
                "userPrompt", userPrompt(snapshot, deepReport, request)
        ));
    }

    private ReputationContentPack parsePack(ResearchSnapshot snapshot, String responseText) throws Exception {
        JsonNode root = objectMapper.readTree(extractJsonObject(responseText));
        JsonNode profileNode = root.path("companyProfile");
        CompanyAiProfile profile = new CompanyAiProfile(
                profileNode.path("shortDescription").asText(""),
                profileNode.path("category").asText(""),
                strings(profileNode.path("products")),
                strings(profileNode.path("advantages")),
                strings(profileNode.path("positiveReviewTopics")),
                strings(profileNode.path("negativeReviewTopics")),
                strings(profileNode.path("factualWarnings"))
        );

        return new ReputationContentPack(
                snapshot,
                profile,
                strings(root.path("utp")),
                strings(root.path("adTexts")),
                strings(root.path("socialPostTopics")),
                strings(root.path("socialPosts")),
                cleanReviewItems(strings(root.path("honestReviewTopics"))),
                cleanReviewItems(strings(root.path("reviewDraftTemplates"))),
                strings(root.path("positiveReviewReplies")),
                strings(root.path("negativeReviewReplies")),
                snapshot.sources().stream().map(CompanySource::url).filter(url -> !url.isBlank()).distinct().toList(),
                strings(root.path("safetyNotes"))
        );
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.isValueNode() ? item.asText("") : item.toString();
            if (!value.isBlank() && !result.contains(value.trim())) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private List<String> cleanReviewItems(List<String> values) {
        return values.stream()
                .map(value -> value == null ? "" : value
                        .replace('[', ' ')
                        .replace(']', ' ')
                        .replaceAll("\\s+", " ")
                        .trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private String extractJsonObject(String text) {
        String clean = text.trim();
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return clean.substring(start, end + 1);
        }

        return clean;
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }

        return value.substring(0, maxLength).trim();
    }

    private List<String> extractEvidenceFacts(ResearchSnapshot snapshot) {
        return snapshot.sources().stream()
                .filter(source -> !isLowTrustFactSource(source))
                .flatMap(source -> List.of(source.title(), source.excerpt()).stream())
                .map(value -> value == null ? "" : value.replaceAll("\\s+", " ").trim())
                .filter(value -> !value.isBlank())
                .filter(value -> containsConcreteFact(value, snapshot))
                .map(value -> limit(value, 360))
                .distinct()
                .limit(25)
                .toList();
    }

    private Map<String, Object> deepResearchPayload(DeepCompanyResearchReport report) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("createdAt", report.createdAt());
        payload.put("model", report.model());
        payload.put("warnings", report.warnings());
        payload.put("qualityChecks", report.qualityChecks().stream()
                .map(check -> Map.of(
                        "label", check.label(),
                        "status", check.status(),
                        "detail", check.detail()
                ))
                .toList());
        payload.put("factSnapshot", Map.of(
                "confirmedFacts", report.factSnapshot().confirmedFacts().stream()
                        .map(fact -> Map.of(
                                "label", fact.label(),
                                "value", fact.value(),
                                "evidence", fact.evidence(),
                                "confidence", fact.confidence()
                        ))
                        .toList(),
                "uncertainFacts", report.factSnapshot().uncertainFacts().stream()
                        .map(fact -> Map.of(
                                "label", fact.label(),
                                "value", fact.value(),
                                "evidence", fact.evidence(),
                                "confidence", fact.confidence()
                        ))
                        .toList()
        ));
        payload.put("sections", report.sections().stream()
                .map(section -> Map.of(
                        "title", section.title(),
                        "body", limit(section.body(), 3200)
                ))
                .toList());
        payload.put("sources", report.sources().stream()
                .limit(30)
                .map(source -> Map.of(
                        "title", source.title(),
                        "url", source.url(),
                        "type", source.type(),
                        "usedFor", source.usedFor(),
                        "confidence", source.confidence(),
                        "note", source.note()
                ))
                .toList());
        if (report.sections().isEmpty()) {
            payload.put("reportMarkdown", limit(report.reportMarkdown(), 24000));
        }
        return payload;
    }

    private List<String> priorityUrls(DeepCompanyResearchReport deepReport, ReputationContentPackRequest request) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (request.publicUrls() != null) {
            request.publicUrls().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(urls::add);
        }
        deepReport.sources().stream()
                .map(DeepCompanyResearchReport.Source::url)
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .filter(this::isPriorityUrl)
                .limit(20)
                .forEach(urls::add);
        return urls.stream().toList();
    }

    private boolean isPriorityUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("2gis.")
                || lower.contains("yandex.")
                || lower.contains("google.com/maps")
                || lower.contains("service")
                || lower.contains("uslug")
                || lower.contains("price")
                || lower.contains("prais")
                || lower.contains("цены")
                || lower.contains("услуг")
                || lower.contains("kontakty")
                || lower.contains("contact")
                || lower.endsWith(".pdf")
                || (!lower.contains("://vk.")
                && !lower.contains("://t.me")
                && !lower.contains("facebook.")
                && !lower.contains("instagram."));
    }

    private boolean isLowTrustFactSource(CompanySource source) {
        String type = source.type();
        return type.contains("catalog_listing")
                || type.contains("competitor_listing")
                || type.contains("unknown_public");
    }

    private boolean containsConcreteFact(String value, ResearchSnapshot snapshot) {
        String lower = value.toLowerCase();
        String companyName = snapshot.companyName().toLowerCase();
        String city = snapshot.city().toLowerCase();
        return (!companyName.isBlank() && lower.contains(companyName))
                || (!city.isBlank() && lower.contains(city))
                || lower.matches(".*\\d[\\d\\s\\-()+]{2,}.*")
                || lower.contains("рейтинг")
                || lower.contains("отзыв")
                || lower.contains("цена")
                || lower.contains("адрес")
                || lower.contains("ул.")
                || lower.contains("улица")
                || lower.contains("сайт");
    }

    private List<String> qualityIssues(
            ReputationContentPack pack,
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request
    ) {
        List<String> issues = new ArrayList<>();
        boolean economy = isEconomyProfile(request);
        int expectedAds = Math.min(boundedCount(request.adTextsCount(), 6, MAX_AD_TEXTS_PER_PACK), economy ? 3 : 5);
        int expectedPosts = Math.min(boundedCount(request.socialPostsCount(), 4, MAX_SOCIAL_POSTS_PER_PACK), economy ? 2 : 5);

        if (pack.utp().isEmpty() || pack.adTexts().size() < expectedAds || pack.reviewDraftTemplates().isEmpty()) {
            issues.add("не хватает обязательных блоков");
        }
        if (pack.socialPosts().size() < expectedPosts) {
            issues.add("мало полноценных постов/статей");
        }

        long humanAdCards = pack.adTexts().stream()
                .filter(text -> isHumanAdCard(text, economy))
                .count();
        if (!pack.adTexts().isEmpty() && humanAdCards < Math.min(pack.adTexts().size(), expectedAds)) {
            issues.add("рекламные карточки слишком сухие или короткие");
        }

        String marketingText = normalizeForQuality(String.join(" ", pack.utp()) + " " + String.join(" ", pack.adTexts()));
        if (containsServiceLabels(marketingText)) {
            issues.add("рекламные тексты похожи на анкету с техническими заголовками");
        }
        if (containsSourceVoice(marketingText)) {
            issues.add("рекламные тексты говорят от лица источников, а не компании");
        }
        if (hasLowVariety(pack.utp(), economy ? 4 : 5)) {
            issues.add("УТП слишком похожи друг на друга");
        }
        if (hasLowVariety(pack.adTexts(), economy ? 3 : 4)) {
            issues.add("рекламные тексты повторяют один и тот же сценарий");
        }
        if (hasStrongOverlap(pack.utp(), pack.adTexts())) {
            issues.add("УТП и рекламные тексты дублируют друг друга");
        }

        long longPosts = pack.socialPosts().stream()
                .filter(text -> text.length() >= (economy ? 500 : 700))
                .count();
        if (!pack.socialPosts().isEmpty() && longPosts < Math.min(pack.socialPosts().size(), expectedPosts)) {
            issues.add("посты слишком короткие");
        }
        long titledPosts = pack.socialPosts().stream()
                .filter(this::hasPostTitle)
                .count();
        if (!pack.socialPosts().isEmpty() && titledPosts < Math.min(pack.socialPosts().size(), expectedPosts)) {
            issues.add("постам не хватает заголовков");
        }

        long readyReviews = pack.reviewDraftTemplates().stream()
                .filter(text -> text.length() >= (economy ? 300 : 450))
                .count();
        if (!pack.reviewDraftTemplates().isEmpty() && readyReviews < Math.min(pack.reviewDraftTemplates().size(), economy ? 2 : 3)) {
            issues.add("черновики отзывов слишком короткие");
        }
        long usefulReviewTopics = pack.honestReviewTopics().stream()
                .filter(this::isUsefulReviewTopic)
                .count();
        if (!pack.honestReviewTopics().isEmpty() && usefulReviewTopics < Math.min(pack.honestReviewTopics().size(), economy ? 3 : 5)) {
            issues.add("темы отзывов похожи на вопросник и не используют УТП/услуги/сценарии");
        }
        long commercialReviewDrafts = pack.reviewDraftTemplates().stream()
                .filter(text -> isCommercialReviewDraft(text, economy))
                .count();
        if (!pack.reviewDraftTemplates().isEmpty() && commercialReviewDrafts < Math.min(pack.reviewDraftTemplates().size(), economy ? 2 : 3)) {
            issues.add("черновики отзывов не связывают личный опыт с УТП, услугами или постами AI-пакета");
        }

        String allText = normalizeForQuality(String.join(" ", pack.utp())
                + " " + String.join(" ", pack.adTexts())
                + " " + String.join(" ", pack.socialPosts())
                + " " + String.join(" ", pack.reviewDraftTemplates()));
        if (allText.contains("коротко объясните клиенту") || allText.contains("адаптируйте формулировку")) {
            issues.add("остались шаблонные инструкции вместо готового текста");
        }

        List<String> baseSignals = baseSignals(snapshot, deepReport, request);
        int requiredBaseMatches = economy ? 1 : Math.min(2, baseSignals.size());
        if (!baseSignals.isEmpty() && countSignalMatches(allText, baseSignals) < requiredBaseMatches) {
            issues.add("не используются базовые данные компании");
        }

        List<String> sourceSignals = sourceSignals(snapshot);
        if (sourceSignals.size() >= 4 && countSignalMatches(allText, sourceSignals) < 2) {
            issues.add("не используются факты из публичных источников");
        }

        List<String> deepSignals = deepResearchSignals(deepReport);
        if (deepSignals.size() >= 4 && countSignalMatches(allText, deepSignals) < 3) {
            issues.add("не используются ключевые факты из глубокого отчета");
        }

        return issues;
    }

    private boolean isHumanAdCard(String value, boolean economy) {
        String normalized = normalizeForQuality(value);
        if (normalized.length() < (economy ? 220 : 260)) {
            return false;
        }
        if (containsServiceLabels(normalized) || containsSourceVoice(normalized)) {
            return false;
        }

        int explanatorySignals = 0;
        for (String signal : List.of(
                "мы ", "помож", "подскаж", "оформ", "возьм", "разбер",
                "обрат", "остав", "заяв", "клиент", "водител", "удоб",
                "без лиш", "спокой", "довер", "стоимост", "срок"
        )) {
            if (normalized.contains(signal)) {
                explanatorySignals++;
            }
        }
        return explanatorySignals >= (economy ? 1 : 2);
    }

    private boolean isUsefulReviewTopic(String value) {
        String normalized = normalizeForQuality(value);
        if (normalized.length() < 58 || isShortQuestionnaireTopic(normalized)) {
            return false;
        }
        return containsReviewCommercialSignal(normalized);
    }

    private boolean isCommercialReviewDraft(String value, boolean economy) {
        String normalized = normalizeForQuality(value);
        if (normalized.length() < (economy ? 320 : 430)) {
            return false;
        }
        if (countPersonalPlaceholders(value) > 0) {
            return false;
        }
        return containsReviewCommercialSignal(normalized);
    }

    private boolean isShortQuestionnaireTopic(String normalized) {
        if (normalized.length() >= 95) {
            return false;
        }
        return normalized.startsWith("как ")
                || normalized.startsWith("что ")
                || normalized.startsWith("какой ")
                || normalized.startsWith("какая ")
                || normalized.startsWith("какие ")
                || normalized.startsWith("насколько ")
                || normalized.startsWith("почему ")
                || normalized.startsWith("был ")
                || normalized.startsWith("была ")
                || normalized.startsWith("были ")
                || normalized.startsWith("удобно ")
                || normalized.startsWith("понравил");
    }

    private boolean containsReviewCommercialSignal(String normalized) {
        for (String signal : List.of(
                "услуг", "товар", "позици", "пакет", "программ", "формат",
                "сценар", "цена", "стоим", "услов", "адрес", "филиал",
                "достав", "самовывоз", "запис", "брон", "консультац",
                "гарант", "срок", "ассортимент", "ремонт", "обслужив",
                "заказ", "утп", "польз", "подходит", "помог", "удоб",
                "выбор", "пост", "акци", "интерьер", "фото", "логист",
                "маршрут", "парков", "вход", "рейтинг", "отзыв"
        )) {
            if (normalized.contains(signal)) {
                return true;
            }
        }
        return false;
    }

    private long countPersonalPlaceholders(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return value.chars().filter(character -> character == '[').count();
    }

    private boolean containsServiceLabels(String normalized) {
        return normalized.contains("заголовок:")
                || normalized.contains("кому:")
                || normalized.contains("зачем:")
                || normalized.contains("почему можно доверять:")
                || normalized.contains("текст:")
                || normalized.contains("следующий шаг:")
                || normalized.contains("условия/сроки:")
                || normalized.contains("источник/уверенность:");
    }

    private boolean containsSourceVoice(String normalized) {
        return normalized.contains("сайт указывает")
                || normalized.contains("сайт заявляет")
                || normalized.contains("сайт описывает")
                || normalized.contains("официальный сайт указывает")
                || normalized.contains("официальный сайт описывает")
                || normalized.contains("2гис указывает")
                || normalized.contains("2гис показывает")
                || normalized.contains("urbanplaces указывает")
                || normalized.contains("по открытым данным")
                || normalized.contains("в открытых данных")
                || normalized.contains("источник подтверждает")
                || normalized.contains("по данным источников");
    }

    private boolean hasPostTitle(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            return false;
        }
        String[] lines = text.split("\\R+");
        String firstLine = lines[0].trim();
        return firstLine.length() >= 12
                && firstLine.length() <= 120
                && lines.length >= 2
                && !firstLine.endsWith(".");
    }

    private boolean hasLowVariety(List<String> values, int minDistinctAngles) {
        if (values.size() < minDistinctAngles) {
            return false;
        }
        long distinctAngles = values.stream()
                .map(this::contentAngle)
                .filter(value -> !value.isBlank())
                .distinct()
                .count();
        return distinctAngles < minDistinctAngles;
    }

    private boolean hasStrongOverlap(List<String> left, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        Set<String> leftAngles = left.stream()
                .map(this::contentAngle)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        long overlapping = right.stream()
                .map(this::contentAngle)
                .filter(leftAngles::contains)
                .count();
        return overlapping >= Math.min(3, right.size());
    }

    private String contentAngle(String value) {
        String normalized = normalizeForQuality(value);
        if (normalized.contains("услуг") || normalized.contains("товар") || normalized.contains("пакет") || normalized.contains("программ")) {
            return "offer";
        }
        if (normalized.contains("маршрут") || normalized.contains("логист") || normalized.contains("достав") || normalized.contains("самовывоз") || normalized.contains("как добраться")) {
            return "logistics";
        }
        if (normalized.contains("стоим") || normalized.contains("эконом") || normalized.contains("доступн") || normalized.contains("переплач")) {
            return "price";
        }
        if (normalized.contains("офис") || normalized.contains("адрес") || normalized.contains("филиал") || normalized.contains("вход") || normalized.contains("этаж")) {
            return "location";
        }
        if (normalized.contains("консультац") || normalized.contains("вопрос") || normalized.contains("обсуд")) {
            return "consultation";
        }
        if (normalized.contains("сопровожд") || normalized.contains("поддерж") || normalized.contains("связ")) {
            return "support";
        }
        if (normalized.contains("запис") || normalized.contains("брон") || normalized.contains("заказ") || normalized.contains("подготов")) {
            return "order_flow";
        }
        if (normalized.contains("семейн") || normalized.contains("дет") || normalized.contains("корпоратив") || normalized.contains("b2b") || normalized.contains("подар")) {
            return "audience";
        }
        if (normalized.contains("спокой") || normalized.contains("тревог") || normalized.contains("ясност") || normalized.contains("понят")) {
            return "clarity";
        }
        if (normalized.contains("гарант") || normalized.contains("отзыв") || normalized.contains("рейтинг") || normalized.contains("довер")) {
            return "trust";
        }
        List<String> tokens = java.util.Arrays.stream(normalized.split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() >= 5)
                .filter(token -> !GENERIC_QUALITY_WORDS.contains(token))
                .limit(4)
                .toList();
        return String.join("-", tokens);
    }

    private List<String> deepResearchSignals(DeepCompanyResearchReport report) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        for (DeepCompanyResearchReport.Section section : report.sections()) {
            String normalizedTitle = normalizeForQuality(section.title());
            boolean important = normalizedTitle.contains("цен")
                    || normalizedTitle.contains("филиал")
                    || normalizedTitle.contains("клиент")
                    || normalizedTitle.contains("репутац")
                    || normalizedTitle.contains("довер")
                    || normalizedTitle.contains("сценари")
                    || normalizedTitle.contains("утп")
                    || normalizedTitle.contains("риск");
            if (!important) {
                continue;
            }
            for (String token : normalizeForQuality(section.body()).split("[^\\p{L}\\p{N}]+")) {
                if (token.length() >= 5 && !GENERIC_QUALITY_WORDS.contains(token)) {
                    signals.add(token);
                }
                if (signals.size() >= 30) {
                    return signals.stream().toList();
                }
            }
        }
        return signals.stream().toList();
    }

    private List<String> baseSignals(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request
    ) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        addSignalAliases(signals, snapshot.companyName());
        addSignalAliases(signals, deepReport.companyName());
        if (shouldRequireSnapshotCity(snapshot, deepReport)) {
            addSignalAliases(signals, snapshot.city());
        }
        if (shouldRequireDeepReportCity(deepReport)) {
            addSignalAliases(signals, deepReport.city());
        }
        addSignalAliases(signals, request.productOrService());
        snapshot.products().stream().limit(3).forEach(value -> addSignalAliases(signals, value));
        return signals.stream().toList();
    }

    private boolean shouldRequireDeepReportCity(DeepCompanyResearchReport deepReport) {
        return !normalizeForQuality(deepReport.city()).isBlank();
    }

    private boolean shouldRequireSnapshotCity(ResearchSnapshot snapshot, DeepCompanyResearchReport deepReport) {
        String city = normalizeForQuality(snapshot.city());
        if (city.isBlank()) {
            return false;
        }

        List<String> deepParts = new ArrayList<>(deepReport.warnings());
        deepParts.addAll(deepReport.sections().stream()
                .map(section -> section.title() + " " + section.body())
                .toList());
        String deepText = normalizeForQuality(String.join(" ", deepParts));
        boolean cityIsDisputed = deepText.contains(city)
                && (deepText.contains("crm-город") || deepText.contains("crm город"))
                && (deepText.contains("не подтверж") || deepText.contains("противореч") || deepText.contains("спорн"));
        return !cityIsDisputed;
    }

    private List<String> sourceSignals(ResearchSnapshot snapshot) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        LinkedHashSet<String> excluded = new LinkedHashSet<>();
        addSignalAliases(excluded, snapshot.companyName());
        addSignalAliases(excluded, snapshot.city());
        snapshot.products().forEach(value -> addSignalAliases(excluded, value));
        for (String fact : extractEvidenceFacts(snapshot)) {
            for (String token : normalizeForQuality(fact).split("[^\\p{L}\\p{N}]+")) {
                if (token.length() >= 5 && !GENERIC_QUALITY_WORDS.contains(token) && !excluded.contains(token)) {
                    signals.add(token);
                }
                if (signals.size() >= 20) {
                    return signals.stream().toList();
                }
            }
        }
        return signals.stream().toList();
    }

    private void addSignal(LinkedHashSet<String> signals, String value) {
        String normalized = normalizeForQuality(value);
        if (normalized.length() >= 3) {
            signals.add(normalized);
        }
    }

    private void addSignalAliases(LinkedHashSet<String> signals, String value) {
        String normalized = normalizeForQuality(value);
        addSignal(signals, normalized);
        for (String token : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 4 && !GENERIC_QUALITY_WORDS.contains(token)) {
                signals.add(token);
            }
        }
    }

    private long countSignalMatches(String text, List<String> signals) {
        return signals.stream()
                .filter(signal -> !signal.isBlank() && text.contains(signal))
                .count();
    }

    private String normalizeForQuality(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ").trim();
    }

    private String valueOrBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private String renderPromptTemplate(String template, Map<String, String> values) {
        String result = valueOrBlank(template);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", valueOrBlank(entry.getValue()));
        }
        return result;
    }

    private int count(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private int boundedCount(Integer value, int fallback, int max) {
        return Math.min(count(value, fallback), max);
    }

    private boolean isEconomyProfile(ReputationContentPackRequest request) {
        return "economy".equalsIgnoreCase(valueOrBlank(request.contentPackProfile()));
    }

    private ReputationContentPackRequest compactRequest(ReputationContentPackRequest request) {
        return new ReputationContentPackRequest(
                request.productOrService(),
                request.manualDescription(),
                request.productsOrServices(),
                request.publicUrls(),
                request.includeCompanyWebsite(),
                Math.min(boundedCount(request.adTextsCount(), 6, MAX_AD_TEXTS_PER_PACK), 4),
                Math.min(boundedCount(request.socialPostsCount(), 4, MAX_SOCIAL_POSTS_PER_PACK), 2),
                Math.min(boundedCount(request.positiveReplyCount(), 6, MAX_POSITIVE_REPLIES_PER_PACK), 4),
                Math.min(boundedCount(request.negativeReplyCount(), 4, MAX_NEGATIVE_REPLIES_PER_PACK), 2),
                request.contentPackProfile(),
                request.deepReportJobId()
        );
    }

    private record ParsedPack(ReputationContentPack pack, boolean compact) {
    }
}
