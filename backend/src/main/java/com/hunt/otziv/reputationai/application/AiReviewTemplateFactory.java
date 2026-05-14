package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewTemplatesRequest;
import com.hunt.otziv.reputationai.config.ContentPackProfile;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReputationReviewTemplatesResult;
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
public class AiReviewTemplateFactory {

    private final OpenAiProvider openAiProvider;
    private final ObjectMapper objectMapper;

    public boolean isOpenAiAvailable() {
        return openAiProvider.isAvailable();
    }

    public Optional<ReputationReviewTemplatesResult> create(
            Long companyId,
            Long deepReportJobId,
            Long contentPackJobId,
            DeepCompanyResearchReport deepReport,
            ReputationContentPack pack,
            ReputationReviewTemplatesRequest request
    ) {
        if (!openAiProvider.isAvailable()) {
            return Optional.empty();
        }

        try {
            AiResponse response = openAiProvider.generateReviewTemplates(new AiRequest(
                    "reputation-review-templates",
                    systemPrompt(),
                    userPrompt(deepReport, pack, request),
                    0.22,
                    true
            ), request.contentPackProfile());
            if (response.text().isBlank()) {
                return Optional.empty();
            }
            ReputationReviewTemplatesResult result = parseResult(
                    companyId,
                    deepReportJobId,
                    contentPackJobId,
                    pack,
                    response.provider(),
                    modelLabel(request.contentPackProfile()),
                    response.text()
            );
            if (result.honestReviewTopics().isEmpty() || result.reviewDraftTemplates().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(result);
        } catch (Exception exception) {
            log.warn("AI review templates generation failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private String systemPrompt() {
        return """
                Ты senior reputation-маркетолог и фактчекер.
                Твоя задача - улучшить только блоки отзывов AI-пакета: honestReviewTopics и reviewDraftTemplates.
                Используй deepResearch как источник фактов, а AI-пакет как источник УТП, рекламных сценариев, постов и позиционирования.
                Не пересобирай весь пакет, не выдумывай личный опыт клиента и не пиши фейковые отзывы.
                Темы должны быть не вопросами, а смысловыми темами для честного отзыва: услуга/товар/пакет + польза/УТП + какой личный штрих клиенту добавить.
                Черновики должны быть полезными полуготовыми отзывами: 60-75% конкретика компании, услуги, УТП, сценария или вывода из поста; 25-40% место для личного опыта клиента.
                В каждом черновике используй одну конкретную тему из УТП, рекламы или постов. Не вставляй всё сразу.
                Не используй квадратные скобки, прочерки-заглушки, шаблонные поля и одинаковые первые фразы.
                Каждый черновик должен начинаться по-разному и использовать разные факты: адрес/филиал, парковка/вход/этаж, путь/логистика, цены/условия, сценарий услуги, доверие, отзывы, ограничения - только если это есть в deepResearch или AI-пакете.
                Верни только валидный JSON без markdown.
                """.stripIndent().trim();
    }

    private String userPrompt(
            DeepCompanyResearchReport deepReport,
            ReputationContentPack pack,
            ReputationReviewTemplatesRequest request
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("manualNotes", request.manualNotes());
        payload.put("tone", request.tone());
        payload.put("topicsCount", request.topicsCount());
        payload.put("draftsCount", request.draftsCount());
        payload.put("companyProfile", pack.companyProfile());
        payload.put("utp", pack.utp());
        payload.put("adTexts", pack.adTexts());
        payload.put("socialPostTopics", pack.socialPostTopics());
        payload.put("socialPosts", pack.socialPosts().stream().map(value -> limit(value, 1400)).toList());
        payload.put("oldHonestReviewTopics", pack.honestReviewTopics());
        payload.put("oldReviewDraftTemplates", pack.reviewDraftTemplates());
        payload.put("deepResearch", deepReportPayload(deepReport));
        payload.put("rules", List.of(
                "Сгенерируй ровно topicsCount тем и draftsCount черновиков, если хватает материала.",
                "Не повторяй старые темы дословно, особенно короткие вопросы.",
                "Каждый черновик должен подсвечивать одну услугу, один сценарий, одно УТП или один вывод из поста.",
                "Личный опыт клиента оставляй короткой вставкой, а не делай её главным содержанием.",
                "Запрещены квадратные скобки, прочерки-заглушки, фразы 'добавьте' внутри черновика, одинаковые начала и одинаковая структура.",
                "Используй разные факты из deepResearch: парковка, вход, этаж, адрес, путь от центра, режим, цены, условия, возраст, чайная зона, фото, безопасность, отзывы - когда эти факты присутствуют.",
                "Если есть ручные приписки manualNotes, используй их как приоритетные акценты, но не нарушай фактчек."
        ));
        return "Улучши блоки отзывов AI-пакета по входным данным:\n" + objectMapper.writeValueAsString(payload);
    }

    private Map<String, Object> deepReportPayload(DeepCompanyResearchReport report) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("companyName", report.companyName());
        payload.put("city", report.city());
        payload.put("warnings", report.warnings());
        payload.put("sections", report.sections().stream()
                .limit(14)
                .map(section -> Map.of(
                        "title", section.title(),
                        "body", limit(section.body(), 1600)
                ))
                .toList());
        payload.put("sources", report.sources().stream()
                .limit(12)
                .map(source -> Map.of(
                        "title", source.title(),
                        "url", source.url(),
                        "note", source.note()
                ))
                .toList());
        return payload;
    }

    private ReputationReviewTemplatesResult parseResult(
            Long companyId,
            Long deepReportJobId,
            Long contentPackJobId,
            ReputationContentPack pack,
            String provider,
            String model,
            String responseText
    ) throws Exception {
        JsonNode root = objectMapper.readTree(responseText);
        return new ReputationReviewTemplatesResult(
                companyId,
                pack.researchSnapshot().companyName(),
                deepReportJobId,
                contentPackJobId,
                provider,
                model,
                cleanItems(strings(root.path("honestReviewTopics"))),
                cleanItems(strings(root.path("reviewDraftTemplates"))),
                strings(root.path("safetyNotes")),
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

    private List<String> cleanItems(List<String> values) {
        return values.stream()
                .map(this::cleanGeneratedText)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
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
        clean = clean.replaceAll("(?i)\\s*добавьте\\s+[^.?!]+[.?!]?", " ").replaceAll("\\s+", " ").trim();
        return clean;
    }

    private String limit(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String clean = value.trim();
        return clean.length() <= limit ? clean : clean.substring(0, limit).trim();
    }

    private String modelLabel(String profileKey) {
        ContentPackProfile profile = ContentPackProfile.fromKey(profileKey);
        return profile == null ? profileKey : profile.model();
    }
}
