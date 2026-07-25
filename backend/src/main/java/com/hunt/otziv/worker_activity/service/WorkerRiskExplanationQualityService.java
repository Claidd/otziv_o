package com.hunt.otziv.worker_activity.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProviderRouter;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import com.hunt.otziv.worker_activity.model.WorkerActivityEvent;
import com.hunt.otziv.worker_activity.model.WorkerRiskExplanationQuality;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.repository.WorkerActivityEventRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerRiskExplanationQualityService {

    private static final Set<String> GENERIC_ANSWERS = Set.of(
            "да", "нет", "ок", "окей", "все нормально", "всё нормально", "сделал", "сделала",
            "готово", "не знаю", "большой заказ", "работал", "работала"
    );
    private static final String SYSTEM_PROMPT = """
            Ты проверяешь только качество рабочего пояснения специалиста по риск-сигналу.
            Не определяй виновность, не назначай штраф и не принимай решение вместо менеджера.
            Сопоставь пояснение с причиной риска и фактическим контекстом.
            Верни только JSON с полями:
            quality: LOGICAL | PARTIAL | CONTRADICTORY | IRRELEVANT | NEEDS_REVIEW,
            confidence: число от 0 до 1,
            reason: короткое объяснение на русском,
            contradictions: массив коротких строк,
            missingFacts: массив коротких строк,
            clarificationQuestion: один конкретный вопрос или пустая строка.
            LOGICAL означает, что ответ прямо объясняет риск и не противоречит фактам.
            PARTIAL означает, что ответ относится к риску, но не объясняет существенную часть.
            CONTRADICTORY означает явное противоречие фактам.
            IRRELEVANT означает, что ответ не отвечает на замечание.
            NEEDS_REVIEW используй только когда данных объективно недостаточно для оценки.
            """;

    private final AiProviderRouter providerRouter;
    private final ReputationAiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final AppSettingService appSettingService;
    private final OrderRepository orderRepository;
    private final WorkerActivityEventRepository activityEventRepository;

    public Result assess(WorkerRiskIncident incident, String explanation) {
        String answer = clean(explanation);
        if (answer.isBlank()) {
            return result(
                    WorkerRiskExplanationQuality.IRRELEVANT,
                    1,
                    "Пояснение пустое",
                    "Напишите, что произошло и почему сработало замечание",
                    "rules"
            );
        }
        String normalized = normalize(answer);
        if (GENERIC_ANSWERS.contains(normalized) || normalized.length() < 8) {
            return result(
                    WorkerRiskExplanationQuality.PARTIAL,
                    0.99,
                    "Ответ слишком общий и не объясняет причину риска",
                    clarificationFor(incident),
                    "rules"
            );
        }
        if (!appSettingService.getBoolean(
                AppSettingService.WORKER_RISK_EXPLANATION_QUALITY_ENABLED,
                true
        )) {
            return result(
                    WorkerRiskExplanationQuality.NEEDS_REVIEW,
                    0,
                    "Автоматическая проверка пояснений выключена",
                    "",
                    "disabled"
            );
        }
        if (!"deepseek".equalsIgnoreCase(providerRouter.activeProviderName())
                || !providerRouter.activeProviderAvailable()) {
            return result(
                    WorkerRiskExplanationQuality.NEEDS_REVIEW,
                    0,
                    "DeepSeek недоступен, пояснение передано менеджеру без автоматического вывода",
                    "",
                    providerRouter.activeProviderName()
            );
        }

        try {
            int timeoutSeconds = Math.max(5, Math.min(60, appSettingService.getInt(
                    AppSettingService.WORKER_RISK_EXPLANATION_AI_TIMEOUT_SECONDS,
                    20
            )));
            String contextJson = objectMapper.writeValueAsString(context(incident, answer));
            AiResponse response = providerRouter.activeProvider().generate(new AiRequest(
                    "worker-risk-explanation-quality",
                    SYSTEM_PROMPT,
                    contextJson,
                    0.1,
                    true,
                    700,
                    Duration.ofSeconds(timeoutSeconds)
            ));
            if (!response.errorMessage().isBlank() || response.text().isBlank()) {
                return result(
                        WorkerRiskExplanationQuality.NEEDS_REVIEW,
                        0,
                        response.errorMessage().isBlank()
                                ? "DeepSeek вернул пустой ответ"
                                : limit(response.errorMessage(), 500),
                        "",
                        response.provider()
                );
            }
            return parse(response);
        } catch (RuntimeException exception) {
            log.warn("Оценка пояснения риска не выполнена incidentId={}: {}",
                    incident == null ? null : incident.getId(),
                    exception.getMessage());
            return result(
                    WorkerRiskExplanationQuality.NEEDS_REVIEW,
                    0,
                    "DeepSeek не смог оценить пояснение: " + limit(exception.getMessage(), 300),
                    "",
                    "deepseek"
            );
        } catch (Exception exception) {
            return result(
                    WorkerRiskExplanationQuality.NEEDS_REVIEW,
                    0,
                    "Ответ DeepSeek не удалось обработать",
                    "",
                    "deepseek"
            );
        }
    }

    private Result parse(AiResponse response) throws Exception {
        String json = stripCodeFence(response.text());
        JsonNode root = objectMapper.readTree(json);
        WorkerRiskExplanationQuality quality;
        try {
            quality = WorkerRiskExplanationQuality.valueOf(
                    root.path("quality").asText("NEEDS_REVIEW").trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            quality = WorkerRiskExplanationQuality.NEEDS_REVIEW;
        }
        double confidence = Math.max(0, Math.min(1, root.path("confidence").asDouble(0)));
        String reason = root.path("reason").asText("DeepSeek не указал причину оценки");
        String missingFacts = arrayText(root.path("missingFacts"));
        String contradictions = arrayText(root.path("contradictions"));
        if (!missingFacts.isBlank()) {
            reason += ". Не хватает фактов: " + missingFacts;
        }
        if (!contradictions.isBlank()) {
            reason += ". Противоречия: " + contradictions;
        }
        reason = limit(reason, 1000);
        String clarification = limit(root.path("clarificationQuestion").asText(""), 1000);
        if (clarification.isBlank()
                && quality != WorkerRiskExplanationQuality.LOGICAL
                && quality != WorkerRiskExplanationQuality.NEEDS_REVIEW) {
            clarification = "Уточните, что именно произошло и почему это объясняет замечание";
        }
        return new Result(
                quality,
                BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP),
                reason,
                clarification,
                response.provider(),
                aiProperties.getDeepseek().getModel(),
                response.inputTokens(),
                response.outputTokens()
        );
    }

    private Map<String, Object> context(WorkerRiskIncident incident, String explanation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("risk", Map.of(
                "ruleCode", safe(incident == null ? null : incident.getRuleCode()),
                "title", safe(incident == null ? null : incident.getTitle()),
                "action", safe(incident == null ? null : incident.getAction()),
                "details", safe(incident == null ? null : incident.getDetails()),
                "createdAt", value(incident == null ? null : incident.getCreatedAt()),
                "orderId", value(incident == null ? null : incident.getOrderId()),
                "reviewId", value(incident == null ? null : incident.getReviewId())
        ));
        result.put("specialistExplanation", explanation);
        result.put("order", orderContext(incident));
        result.put("recentWorkerActivity", activityContext(incident));
        return result;
    }

    private Map<String, Object> orderContext(WorkerRiskIncident incident) {
        if (incident == null || incident.getOrderId() == null) {
            return Map.of();
        }
        try {
            return orderRepository.findById(incident.getOrderId())
                    .map(this::orderContext)
                    .orElse(Map.of());
        } catch (RuntimeException exception) {
            return Map.of("loadError", true);
        }
    }

    private Map<String, Object> orderContext(Order order) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("createdDate", value(order.getCreated()));
        result.put("statusChangedAt", value(order.getStatusChangedAt()));
        result.put("amount", order.getAmount());
        result.put("completedCounter", order.getCounter());
        result.put("complete", order.isComplete());
        result.put("status", order.getStatus() == null ? "" : safe(order.getStatus().getTitle()));
        result.put("company", order.getCompany() == null ? "" : safe(order.getCompany().getTitle()));
        return result;
    }

    private List<Map<String, Object>> activityContext(WorkerRiskIncident incident) {
        if (incident == null || incident.getWorkerUserId() == null) {
            return List.of();
        }
        LocalDateTime since = (incident.getCreatedAt() == null ? LocalDateTime.now() : incident.getCreatedAt())
                .minusHours(24);
        try {
            return activityEventRepository
                    .findTop50ByWorkerUserIdAndActionInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                            incident.getWorkerUserId(),
                            Arrays.asList(WorkerActivityAction.values()),
                            since
                    )
                    .stream()
                    .limit(15)
                    .map(this::activityContext)
                    .toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private Map<String, Object> activityContext(WorkerActivityEvent event) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("at", value(event.getCreatedAt()));
        result.put("action", event.getAction() == null ? "" : event.getAction().name());
        result.put("orderId", value(event.getOrderId()));
        result.put("reviewId", value(event.getReviewId()));
        result.put("details", limit(event.getDetails(), 500));
        return result;
    }

    private String clarificationFor(WorkerRiskIncident incident) {
        String title = clean(incident == null ? null : incident.getTitle());
        return title.isBlank()
                ? "Что именно произошло и почему действие было выполнено таким образом?"
                : "Что именно произошло по замечанию «" + limit(title, 160) + "»?";
    }

    private Result result(
            WorkerRiskExplanationQuality quality,
            double confidence,
            String reason,
            String clarification,
            String provider
    ) {
        return new Result(
                quality,
                BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP),
                limit(reason, 1000),
                limit(clarification, 1000),
                provider,
                "",
                0,
                0
        );
    }

    private String stripCodeFence(String value) {
        String text = clean(value);
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLine = text.indexOf('\n');
        int closing = text.lastIndexOf("```");
        return firstLine >= 0 && closing > firstLine
                ? text.substring(firstLine + 1, closing).trim()
                : text;
    }

    private String arrayText(JsonNode node) {
        if (node == null || !node.isArray()) {
            return "";
        }
        java.util.List<String> values = new java.util.ArrayList<>();
        node.forEach(item -> {
            String value = clean(item.asText(""));
            if (!value.isBlank()) {
                values.add(limit(value, 180));
            }
        });
        return String.join("; ", values);
    }

    private String normalize(String value) {
        return clean(value)
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String safe(String value) {
        return limit(value, 1500);
    }

    private String limit(String value, int max) {
        String text = clean(value);
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }

    private Object value(Object value) {
        return value == null ? "" : value;
    }

    public record Result(
            WorkerRiskExplanationQuality quality,
            BigDecimal confidence,
            String reason,
            String clarificationQuestion,
            String provider,
            String model,
            int inputTokens,
            int outputTokens
    ) {
    }
}
