package com.hunt.otziv.reputationai.infrastructure.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.AiRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@Slf4j
@RequiredArgsConstructor
public class OpenAiResponsesClient {

    static {
        // Java disables Basic proxy auth for HTTPS CONNECT unless this list is cleared.
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
    }

    private final ReputationAiProperties properties;
    private final ObjectMapper objectMapper;

    public boolean isAvailable() {
        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        return !isBlank(openai.getApiKey())
                && !isBlank(openai.getBaseUrl())
                && !isBlank(openai.getModel());
    }

    public OpenAiResponseResult createTextResponse(AiRequest request) {
        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", openai.getModel());
        body.put("instructions", request.systemPrompt());
        body.put("input", request.userPrompt());
        body.put("temperature", request.temperature());
        body.put("max_output_tokens", openai.getMaxOutputTokens());
        if (request.jsonObject()) {
            body.put("text", Map.of("format", Map.of("type", "json_object")));
        }

        return postResponse(body, openai.getTimeout());
    }

    public OpenAiResponseResult createContentPackResponse(AiRequest request, String profileKey) {
        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        OpenAiContentPackOptions options = OpenAiContentPackOptions.fromProfile(openai, profileKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", options.model());
        body.put("instructions", request.systemPrompt());
        body.put("input", request.userPrompt());
        body.put("max_output_tokens", options.maxOutputTokens());
        if (!isBlank(options.reasoningEffort())) {
            body.put("reasoning", Map.of("effort", options.reasoningEffort()));
        }
        if (request.jsonObject()) {
            body.put("text", Map.of("format", Map.of("type", "json_object")));
        }

        return postResponse(body, options.timeout());
    }

    public OpenAiResponseResult createDeepResearchResponse(String instructions, String input) {
        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        ReputationAiProperties.OpenAi.DeepResearch deep = openai.getDeepResearch();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", deep.getModel());
        body.put("instructions", instructions);
        body.put("input", input);
        body.put("tools", List.of(Map.of("type", "web_search_preview")));
        body.put("max_tool_calls", deep.getMaxToolCalls());
        body.put("max_output_tokens", deep.getMaxOutputTokens());
        body.put("text", Map.of("format", Map.of(
                "type", "json_schema",
                "name", "company_deep_research_report",
                "strict", true,
                "schema", deepResearchSchema()
        )));
        if (deep.isBackground()) {
            body.put("background", true);
        }

        return postResponse(body, deep.getTimeout());
    }

    public OpenAiResponseResult createResearchReportResponse(String instructions, String input, String profileKey) {
        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        ReputationAiProperties.OpenAi.ResearchReport report = openai.getResearchReport();
        OpenAiResearchReportOptions options = OpenAiResearchReportOptions.fromProfile(report, profileKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", options.model());
        body.put("instructions", instructions);
        body.put("input", input);
        body.put("tools", List.of(Map.of(
                "type", "web_search_preview",
                "search_context_size", options.searchContextSize()
        )));
        body.put("max_tool_calls", options.maxToolCalls());
        body.put("max_output_tokens", options.maxOutputTokens());
        if (!isBlank(options.reasoningEffort())) {
            body.put("reasoning", Map.of("effort", options.reasoningEffort()));
        }
        body.put("text", Map.of("format", Map.of(
                "type", "json_schema",
                "name", "company_research_report",
                "strict", true,
                "schema", deepResearchSchema()
        )));

        return postResponse(body, options.timeout());
    }

    private OpenAiResponseResult postResponse(Map<String, Object> body, Duration timeout) {
        if (!isAvailable()) {
            return errorResult("", "", "OpenAI не настроен.");
        }

        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        String fallbackModel = Objects.toString(body.get("model"), openai.getModel());
        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(body);
        } catch (Exception exception) {
            log.warn("OpenAI Responses API request serialization failed: {}", exception.getMessage());
            return errorResult("", fallbackModel, "Не удалось подготовить запрос OpenAI: " + exception.getMessage());
        }

        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(openai.getBaseUrl() + "/responses"))
                        .timeout(timeout == null ? Duration.ofSeconds(60) : timeout)
                        .header("Authorization", "Bearer " + openai.getApiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warn("OpenAI Responses API returned HTTP {} on attempt {}/{}: {}",
                            response.statusCode(),
                            attempt,
                            maxAttempts,
                            limit(response.body(), 800));
                    if (attempt < maxAttempts && isRetryableStatus(response.statusCode())) {
                        sleepBeforeRetry(response, attempt);
                        continue;
                    }
                    return errorResult("", fallbackModel, openAiHttpError(response.statusCode(), response.body()));
                }

                JsonNode root = objectMapper.readTree(response.body());
                if (Boolean.TRUE.equals(body.get("background")) && extractOutputText(root).isBlank()) {
                    return pollResponse(root.path("id").asText(""), timeout, fallbackModel);
                }
                return parseResponse(root, fallbackModel);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return errorResult("", fallbackModel, "Запрос OpenAI был прерван.");
            } catch (Exception exception) {
                log.warn("OpenAI Responses API request failed on attempt {}/{}: {}",
                        attempt,
                        maxAttempts,
                        exception.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        sleepBeforeRetry(null, attempt);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        return new OpenAiResponseResult("", "", fallbackModel, 0, 0);
                    }
                    continue;
                }
                return errorResult("", fallbackModel, "Запрос OpenAI завершился ошибкой: " + exception.getMessage());
            }
        }

        return errorResult("", fallbackModel, "OpenAI не вернул ответ после повторных попыток.");
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 408
                || statusCode == 409
                || statusCode == 429
                || statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }

    private void sleepBeforeRetry(HttpResponse<String> response, int attempt) throws InterruptedException {
        long delaySeconds = response == null
                ? Math.min(20, 4L * attempt)
                : retryAfterSeconds(response, attempt);
        Thread.sleep(Duration.ofSeconds(delaySeconds).toMillis());
    }

    private long retryAfterSeconds(HttpResponse<String> response, int attempt) {
        return response.headers()
                .firstValue("Retry-After")
                .flatMap(value -> {
                    try {
                        return java.util.Optional.of(Long.parseLong(value.trim()));
                    } catch (NumberFormatException exception) {
                        return java.util.Optional.empty();
                    }
                })
                .map(value -> Math.max(1L, Math.min(60L, value)))
                .orElse(Math.min(30L, 6L * attempt));
    }

    private OpenAiResponseResult pollResponse(String responseId, Duration timeout, String fallbackModel) {
        if (isBlank(responseId)) {
            return errorResult("", fallbackModel, "OpenAI не вернул идентификатор фонового ответа.");
        }

        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        long deadline = System.nanoTime() + (timeout == null ? Duration.ofMinutes(8) : timeout).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(3000);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(openai.getBaseUrl() + "/responses/" + responseId))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + openai.getApiKey())
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warn("OpenAI Responses API poll returned HTTP {}: {}", response.statusCode(), limit(response.body(), 800));
                    return errorResult(responseId, fallbackModel, openAiHttpError(response.statusCode(), response.body()));
                }

                JsonNode root = objectMapper.readTree(response.body());
                String status = root.path("status").asText("");
                String text = extractOutputText(root);
                if (!text.isBlank() || "completed".equalsIgnoreCase(status)) {
                    return parseResponse(root, fallbackModel);
                }
                if ("failed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status) || "incomplete".equalsIgnoreCase(status)) {
                    log.warn("OpenAI Responses API background response {} finished with status {}", responseId, status);
                    return parseResponse(root, fallbackModel);
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return errorResult(responseId, fallbackModel, "Ожидание фонового ответа OpenAI было прервано.");
            } catch (Exception exception) {
                log.warn("OpenAI Responses API poll failed: {}", exception.getMessage());
                return errorResult(responseId, fallbackModel, "Проверка фонового ответа OpenAI завершилась ошибкой: " + exception.getMessage());
            }
        }

        log.warn("OpenAI Responses API background response {} timed out", responseId);
        return errorResult(responseId, fallbackModel, "Фоновый ответ OpenAI не завершился за отведенное время.");
    }

    private OpenAiResponseResult parseResponse(JsonNode root, String fallbackModel) {
        String text = extractOutputText(root);
        JsonNode usage = root.path("usage");
        return new OpenAiResponseResult(
                root.path("id").asText(""),
                text,
                root.path("model").asText(fallbackModel),
                usage.path("input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0)
        );
    }

    private OpenAiResponseResult errorResult(String responseId, String fallbackModel, String message) {
        return new OpenAiResponseResult(responseId, "", fallbackModel, 0, 0, limit(message, 1000));
    }

    private String openAiHttpError(int statusCode, String body) {
        String message = extractOpenAiErrorMessage(body);
        if (message.isBlank()) {
            message = limit(body, 800);
        }
        if (message.isBlank()) {
            message = "пустое тело ответа";
        }
        return "OpenAI вернул HTTP " + statusCode + ": " + message;
    }

    private String extractOpenAiErrorMessage(String body) {
        if (isBlank(body)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("error").path("message").asText("");
        } catch (Exception exception) {
            return "";
        }
    }

    private HttpClient httpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));

        ReputationAiProperties.OpenAi.Proxy proxy = properties.getOpenai().getProxy();
        if (proxy.isEnabled() && !isBlank(proxy.getHost())) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
            if (!isBlank(proxy.getUsername())) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(proxy.getUsername(), proxy.getPassword().toCharArray());
                    }
                });
            }
        }

        return builder.build();
    }

    private Map<String, Object> deepResearchSchema() {
        Map<String, Object> section = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "title", Map.of(
                                "type", "string",
                                "description", "Название тематического блока отчёта: например, Краткая сводка, Профиль бизнеса, Услуги, товары и цены."
                        ),
                        "body", Map.of(
                                "type", "string",
                                "description", "Самостоятельный markdown-фрагмент раздела. Для услуг, товаров и цен содержит таблицу с позициями, условиями, ценами и источниками, если данные найдены. Для клиентского опыта сохраняет интерьер и экстерьер филиалов, если это подтверждено источниками. Для оценки компании включает репутационные темы, доверие, возражения, сценарии и идеи контента."
                        )
                ),
                "required", List.of("title", "body")
        );
        Map<String, Object> source = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "title", Map.of("type", "string"),
                        "url", Map.of("type", "string"),
                        "note", Map.of(
                                "type", "string",
                                "description", "Какие ключевые факты подтверждает источник: цены, адрес, правила, отзывы, акции или спорные данные."
                        )
                ),
                "required", List.of("title", "url", "note")
        );

        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "sections", Map.of(
                                "type", "array",
                                "description", "Тематические markdown-блоки для интерфейса и сохраненного отчёта. Держи стабильный универсальный порядок: сводка, профиль бизнеса, услуги/товары/цены, филиалы, клиентский опыт с интерьером и экстерьером, репутационные темы, доверие, сценарии и УТП, сотрудники, условия, сроки/аудитория, риски и возражения, что ещё собирать, идеи контента, главный вывод.",
                                "minItems", 12,
                                "maxItems", 15,
                                "items", section
                        ),
                        "sources", Map.of("type", "array", "items", source),
                        "warnings", Map.of("type", "array", "items", Map.of("type", "string"))
                ),
                "required", List.of("sections", "sources", "warnings")
        );
    }

    private String extractOutputText(JsonNode root) {
        String outputText = root.path("output_text").asText("");
        if (!outputText.isBlank()) {
            return outputText;
        }

        StringBuilder result = new StringBuilder();
        for (JsonNode output : root.path("output")) {
            for (JsonNode content : output.path("content")) {
                String type = content.path("type").asText("");
                if ("output_text".equals(type) || "text".equals(type)) {
                    String text = content.path("text").asText("");
                    if (!text.isBlank()) {
                        if (!result.isEmpty()) {
                            result.append("\n");
                        }
                        result.append(text);
                    }
                }
            }
        }
        return result.toString().trim();
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength).trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
