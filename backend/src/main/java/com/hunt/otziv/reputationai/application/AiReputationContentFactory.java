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

    private final OpenAiProvider openAiProvider;
    private final ObjectMapper objectMapper;

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
                return Optional.empty();
            }

            ReputationContentPack pack = parsePack(snapshot, response.text());
            if (pack.utp().isEmpty() && pack.adTexts().isEmpty() && pack.reviewDraftTemplates().isEmpty()) {
                return Optional.empty();
            }

            List<String> qualityIssues = qualityIssues(pack, snapshot, deepReport, request);
            if (!qualityIssues.isEmpty()) {
                log.warn("AI content pack quality retry: {}", String.join("; ", qualityIssues));
                Optional<ReputationContentPack> revised = reviseLowQualityPack(snapshot, deepReport, request, response.text(), qualityIssues);
                if (revised.isPresent()) {
                    return revised;
                }
                return Optional.empty();
            }

            return Optional.of(pack);
        } catch (Exception exception) {
            log.warn("AI content pack generation failed: {}", exception.getMessage());
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
                return Optional.empty();
            }

            ReputationContentPack pack = parsePack(snapshot, response.text());
            List<String> revisedIssues = qualityIssues(pack, snapshot, deepReport, request);
            if (!revisedIssues.isEmpty()) {
                log.warn("AI content pack revision still low quality: {}", String.join("; ", revisedIssues));
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
        return """
                Предыдущий JSON был отклонен контролем качества.
                Причины: %s
                
                Перепиши пакет полностью. Обязательные правила:
                - главным источником считай deepResearch: репутационные темы, доверие, возражения, сценарии, услуги/цены, филиалы, интерьер/экстерьер и риски;
                - прямо используй конкретные факты из deepResearch, evidenceFacts и trusted/review sources: названия сценариев, город, адреса, рейтинг, количество отзывов, возраст, цены, сайт, карточки, товары или услуги;
                - sources с типами catalog_listing, competitor_listing и unknown_public не используй как факты о компании; это только внешний контекст выдачи;
                - УТП и рекламные тексты должны звучать как работа сильной маркетинговой команды: конкретный инсайт, выгода для клиента, доказательство, честный CTA;
                - socialPosts должны быть полноценными статьями, а не заголовками и не короткими заметками;
                - reviewDraftTemplates должны быть почти готовыми отзывами, но с честными местами для личной проверки клиента;
                - если факт сомнительный, напиши предупреждение в factualWarnings или safetyNotes, но не теряй остальную конкретику.
                
                Исходное задание и данные:
                %s
                
                Отклоненный ответ:
                %s
                """.formatted(
                String.join("; ", qualityIssues),
                userPrompt(snapshot, deepReport, request),
                limit(previousResponse, 6000)
        );
    }

    private String systemPrompt() {
        return """
                Ты элитная команда маркетинга в одном лице: бренд-стратег, performance-маркетолог, SMM-редактор, reputation manager и строгий фактчекер.
                Твоя задача - превратить глубокое исследование компании в сильный AI-пакет для репутации, рекламы, отзывов и соцсетей.
                Главный источник истины - deepResearch. Быстрый слепок и sources используй как дополнительный контекст.
                Используй только факты из deepResearch, входного слепка компании и публичных источников.
                Не выдумывай адреса, даты, товары, услуги, награды, цены, сроки и гарантии.
                Если факта нет, формулируй осторожно: "можно уточнить", "клиент может отметить, если это было в опыте".
                Каждый рекламный текст, УТП, статья и черновик отзыва должны использовать конкретику: город, адрес, сайт, рейтинг, отзывы, сценарии, цены, возраст, формат, интерьер/экстерьер, доверие, возражения, контакты, товары или услуги.
                Пиши как лучшие маркетологи: не общими словами, а через инсайт, клиентский сценарий, доказательство, выгоду и честный следующий шаг.
                Запрещены пустые формулы вроде "индивидуальный подход", "высокое качество", "лучший сервис", если рядом нет конкретного факта из исследования.
                Посты для соцсетей должны быть полноценными готовыми статьями: 900-1600 знаков, с заголовком, лидом, 3-5 смысловыми абзацами и мягким CTA.
                Черновики отзывов должны быть почти готовыми текстами для реальных клиентов: 500-900 знаков, с конкретными деталями компании, но без утверждения непроверенного личного опыта.
                В черновиках отзывов оставляй только 2-4 короткие вставки в квадратных скобках для того, что обязан подтвердить сам клиент.
                Не делай короткие заголовки вместо статей. Не используй общие фразы без фактов, если во входных данных есть конкретика.
                Верни только валидный JSON без markdown.
                """;
    }

    private String userPrompt(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request
    ) throws Exception {
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
        payload.put("evidenceFacts", extractEvidenceFacts(snapshot));
        payload.put("request", Map.of(
                "productOrService", valueOrBlank(request.productOrService()),
                "manualDescription", valueOrBlank(request.manualDescription()),
                "adTextsCount", count(request.adTextsCount(), 10),
                "socialPostsCount", count(request.socialPostsCount(), 10),
                "positiveReplyCount", count(request.positiveReplyCount(), 10),
                "negativeReplyCount", count(request.negativeReplyCount(), 5)
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

        return """
                Подготовь структурированный AI-пакет компании.
                Главный материал - deepResearch: это уже собранное исследование компании. Быстрый snapshot нужен только как дополнительная CRM/поисковая подложка.
                Сделай тексты конкретными: используй название, город, категорию, найденные товары/услуги, цены, филиалы, интерьер/экстерьер, репутационные темы, доверие, возражения, сценарии и факты из sources.
                Если источников или подтверждений мало, явно добавь factualWarnings и safetyNotes, но не усиливай непроверенные утверждения.
                Требование к качеству:
                - companyProfile: сжатое позиционирование, продукты, преимущества, репутационные плюсы/минусы и предупреждения;
                - utp: 7-10 сильных УТП, каждое по формуле "инсайт/сценарий - факт - выгода";
                - adTexts: рекламные объявления 120-240 знаков, с конкретикой, сильным углом и действием;
                - socialPostTopics: темы-планы с аудиторией, сценарием и смыслом поста, не просто заголовки;
                - socialPosts: полноценные статьи по темам socialPostTopics, 900-1600 знаков каждая, с хорошим лидом и мягким CTA;
                - honestReviewTopics: конкретные темы, по которым клиенту легко вспомнить реальный опыт;
                - reviewDraftTemplates: почти готовые отзывы 500-900 знаков, но с короткими местами для проверки клиентом;
                - positiveReviewReplies/negativeReviewReplies: ответы компании с учетом ниши, возражений и репутационного тона.
                Количество элементов в списках ориентируй на request.*Count.
                
                Входные данные:
                """ + objectMapper.writeValueAsString(payload);
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
                strings(root.path("honestReviewTopics")),
                strings(root.path("reviewDraftTemplates")),
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
                        "note", source.note()
                ))
                .toList());
        if (report.sections().isEmpty()) {
            payload.put("reportMarkdown", limit(report.reportMarkdown(), 24000));
        }
        return payload;
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
        int expectedAds = Math.min(count(request.adTextsCount(), 10), 5);
        int expectedPosts = Math.min(count(request.socialPostsCount(), 10), 5);

        if (pack.utp().isEmpty() || pack.adTexts().size() < expectedAds || pack.reviewDraftTemplates().isEmpty()) {
            issues.add("не хватает обязательных блоков");
        }
        if (pack.socialPosts().size() < expectedPosts) {
            issues.add("мало полноценных постов/статей");
        }

        long longPosts = pack.socialPosts().stream()
                .filter(text -> text.length() >= 700)
                .count();
        if (!pack.socialPosts().isEmpty() && longPosts < Math.min(pack.socialPosts().size(), expectedPosts)) {
            issues.add("посты слишком короткие");
        }

        long readyReviews = pack.reviewDraftTemplates().stream()
                .filter(text -> text.length() >= 450)
                .count();
        if (!pack.reviewDraftTemplates().isEmpty() && readyReviews < Math.min(pack.reviewDraftTemplates().size(), 3)) {
            issues.add("черновики отзывов слишком короткие");
        }

        String allText = normalizeForQuality(String.join(" ", pack.utp())
                + " " + String.join(" ", pack.adTexts())
                + " " + String.join(" ", pack.socialPosts())
                + " " + String.join(" ", pack.reviewDraftTemplates()));
        if (allText.contains("коротко объясните клиенту") || allText.contains("адаптируйте формулировку")) {
            issues.add("остались шаблонные инструкции вместо готового текста");
        }

        List<String> baseSignals = baseSignals(snapshot, request);
        if (!baseSignals.isEmpty() && countSignalMatches(allText, baseSignals) < Math.min(2, baseSignals.size())) {
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

    private List<String> baseSignals(ResearchSnapshot snapshot, ReputationContentPackRequest request) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        addSignal(signals, snapshot.companyName());
        addSignal(signals, snapshot.city());
        addSignal(signals, request.productOrService());
        snapshot.products().stream().limit(3).forEach(value -> addSignal(signals, value));
        return signals.stream().toList();
    }

    private List<String> sourceSignals(ResearchSnapshot snapshot) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        LinkedHashSet<String> excluded = new LinkedHashSet<>();
        addSignal(excluded, snapshot.companyName());
        addSignal(excluded, snapshot.city());
        snapshot.products().forEach(value -> addSignal(excluded, value));
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

    private int count(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
