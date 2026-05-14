package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.api.dto.ReputationSingleReviewDraftRequest;
import com.hunt.otziv.reputationai.config.ContentPackProfile;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReputationSingleReviewDraftResult;
import com.hunt.otziv.reputationai.infrastructure.ai.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.OpenAiProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class AiSingleReviewDraftFactory {

    private final OpenAiProvider openAiProvider;
    private final ObjectMapper objectMapper;
    private final ReviewSafetyService reviewSafetyService;

    public boolean isOpenAiAvailable() {
        return openAiProvider.isAvailable();
    }

    public Optional<ReputationSingleReviewDraftResult> create(
            Long companyId,
            Long deepReportJobId,
            Long contentPackJobId,
            DeepCompanyResearchReport deepReport,
            ReputationContentPack pack,
            ReputationSingleReviewDraftRequest request,
            String selectedIdea,
            List<String> fallbackFacts
    ) {
        if (!openAiProvider.isAvailable()) {
            return Optional.empty();
        }

        try {
            AiResponse response = openAiProvider.generateSingleReviewDraft(new AiRequest(
                    "reputation-single-review-draft",
                    systemPrompt(),
                    userPrompt(deepReport, pack, request, selectedIdea, fallbackFacts),
                    0.24,
                    true
            ), request.contentPackProfile());
            if (response.text().isBlank()) {
                throwIfAiError(response);
                return Optional.empty();
            }
            ReputationSingleReviewDraftResult result = parseResult(
                    companyId,
                    deepReportJobId,
                    contentPackJobId,
                    pack,
                    response.provider(),
                    modelLabel(request.contentPackProfile()),
                    request.style(),
                    response.text(),
                    selectedIdea,
                    fallbackFacts
            );
            if (result.draft().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(result);
        } catch (Exception exception) {
            log.warn("AI single review draft generation failed: {}", exception.getMessage());
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

    private String systemPrompt() {
        return """
                Ты senior reputation-маркетолог и редактор честных отзывов.
                Напиши один черновик отзыва на русском языке по выбранной идее.
                Используй deepResearch как источник фактов, а AI-пакет как источник УТП, рекламных сценариев, постов и позиционирования.
                Черновик не должен изображать несуществующий личный опыт: 60-75% текста - проверяемая конкретика компании/услуги/УТП/условий, 25-40% - короткие места для реального опыта клиента.
                Не используй квадратные скобки, прочерки-заглушки и шаблонные поля.
                Не начинай каждый раз одинаково: выбери живое начало под выбранную идею.
                Не используй слишком восторженные обещания, абсолюты, фейковые эмоции, гарантии и выдуманные факты.
                Не вставляй все факты сразу: держись одной идеи, но добавь 2-5 индивидуальных фактов из deepResearch или AI-пакета. Особенно полезны адрес/филиал, парковка, вход, этаж, путь/логистика, режим, цена/условия, возрастные ограничения, чайная зона, фото, безопасность, отзывы.
                Верни только валидный JSON без markdown.
                """.stripIndent().trim();
    }

    private String userPrompt(
            DeepCompanyResearchReport deepReport,
            ReputationContentPack pack,
            ReputationSingleReviewDraftRequest request,
            String selectedIdea,
            List<String> fallbackFacts
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("selectedIdea", selectedIdea);
        payload.put("style", request.style());
        payload.put("length", request.length());
        payload.put("manualNotes", request.manualNotes());
        payload.put("companyProfile", pack.companyProfile());
        payload.put("utp", pack.utp());
        payload.put("adTexts", pack.adTexts());
        payload.put("socialPostTopics", pack.socialPostTopics());
        payload.put("socialPosts", pack.socialPosts().stream().map(value -> limit(value, 1200)).toList());
        payload.put("honestReviewTopics", pack.honestReviewTopics());
        payload.put("reviewDraftTemplates", pack.reviewDraftTemplates());
        payload.put("fallbackFacts", fallbackFacts);
        payload.put("deepResearch", deepReportPayload(deepReport));
        payload.put("rules", List.of(
                "Верни draft длиной 450-900 знаков, если length=medium; короче для short, подробнее для long.",
                "Черновик должен быть полезен клиенту: он дописывает свой опыт, а не придумывает рекламу с нуля.",
                "Выбери 2-5 релевантных фактов под selectedIdea, не перегружай текст.",
                "Запрещены квадратные скобки, прочерки-заглушки и одинаковая структура из старых черновиков.",
                "Если manualNotes не пустой, учитывай его как акцент."
        ));
        return "Сгенерируй один честный черновик отзыва по входным данным:\n" + objectMapper.writeValueAsString(payload);
    }

    private Map<String, Object> deepReportPayload(DeepCompanyResearchReport report) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("companyName", report.companyName());
        payload.put("city", report.city());
        payload.put("warnings", report.warnings());
        payload.put("sections", report.sections().stream()
                .limit(12)
                .map(section -> Map.of(
                        "title", section.title(),
                        "body", limit(section.body(), 1400)
                ))
                .toList());
        payload.put("sources", report.sources().stream()
                .limit(10)
                .map(source -> Map.of(
                        "title", source.title(),
                        "url", source.url(),
                        "note", source.note()
                ))
                .toList());
        return payload;
    }

    private ReputationSingleReviewDraftResult parseResult(
            Long companyId,
            Long deepReportJobId,
            Long contentPackJobId,
            ReputationContentPack pack,
            String provider,
            String model,
            String style,
            String responseText,
            String fallbackIdea,
            List<String> fallbackFacts
    ) throws Exception {
        JsonNode root = objectMapper.readTree(responseText);
        String idea = firstNonBlank(root.path("idea").asText(""), fallbackIdea);
        String draft = cleanGeneratedText(root.path("draft").asText(""));
        List<String> sourceFacts = strings(root.path("sourceFacts"));
        if (sourceFacts.isEmpty()) {
            sourceFacts = fallbackFacts;
        }
        List<String> safetyNotes = strings(root.path("safetyNotes"));
        if (safetyNotes.isEmpty()) {
            safetyNotes = defaultSafetyNotes();
        }
        return new ReputationSingleReviewDraftResult(
                companyId,
                pack.researchSnapshot().companyName(),
                deepReportJobId,
                contentPackJobId,
                provider,
                model,
                idea,
                style,
                draft,
                sourceFacts,
                safetyNotes,
                reviewSafetyService.check(draft, sourceFacts),
                LocalDateTime.now()
        );
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (!node.isArray()) {
            return values;
        }
        node.forEach(item -> {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return values;
    }

    private List<String> defaultSafetyNotes() {
        return List.of("Перед публикацией клиент должен добавить или проверить свой реальный опыт, не меняя факты компании.");
    }

    private String cleanGeneratedText(String value) {
        if (value == null) {
            return "";
        }
        String clean = value
                .replace('[', ' ')
                .replace(']', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return clean.replaceAll("(?i)\\s*добавьте\\s+[^.?!]+[.?!]?", " ").replaceAll("\\s+", " ").trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String modelLabel(String profileKey) {
        ContentPackProfile profile = ContentPackProfile.fromKey(profileKey);
        return profile == null ? profileKey : profile.model();
    }

    private String limit(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= limit ? clean : clean.substring(0, limit).trim();
    }
}
