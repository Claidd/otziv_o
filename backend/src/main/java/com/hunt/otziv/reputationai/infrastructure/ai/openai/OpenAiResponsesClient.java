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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class OpenAiResponsesClient {
    private static final Pattern RETRY_AFTER_MESSAGE = Pattern.compile(
            "Please try again in ([0-9]+(?:\\.[0-9]+)?)s",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RETRY_AFTER_RU_MESSAGE = Pattern.compile(
            "через\\s+([0-9]+(?:\\.[0-9]+)?)\\s*с",
            Pattern.CASE_INSENSITIVE
    );

    static {
        // Java disables Basic proxy auth for HTTP proxying and HTTPS CONNECT unless these lists are cleared.
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
    }

    private final ReputationAiProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicReference<OpenAiLastCheck> lastCheck = new AtomicReference<>(
            new OpenAiLastCheck(null, null, "not_checked", "Проверка OpenAI ещё не запускалась.")
    );

    public record OpenAiLastCheck(
            LocalDateTime checkedAt,
            Integer httpStatus,
            String status,
            String message
    ) {
        public OpenAiLastCheck {
            status = status == null ? "" : status.trim();
            message = message == null ? "" : message.trim();
        }
    }

    public boolean isAvailable() {
        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        return !isBlank(openai.getApiKey())
                && !isBlank(openai.getBaseUrl())
                && !isBlank(openai.getModel());
    }

    public OpenAiLastCheck lastCheck() {
        return lastCheck.get();
    }

    public OpenAiLastCheck checkConnection() {
        if (!isAvailable()) {
            return rememberCheck("not_configured", null, "OpenAI не настроен: укажите OPENAI_API_KEY и модель.");
        }

        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(openai.getBaseUrl() + "/models"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + openai.getApiKey())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return rememberCheck("ok", response.statusCode(), "OpenAI доступен по текущему маршруту.");
            }
            return rememberCheck("http_error", response.statusCode(), openAiHttpError(response.statusCode(), response.body()));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return rememberCheck("network_error", null, "Проверка OpenAI была прервана.");
        } catch (Exception exception) {
            return rememberCheck("network_error", null, openAiTransportError("Проверка OpenAI", exception));
        }
    }

    public OpenAiResponseResult createTextResponse(AiRequest request) {
        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", openai.getModel());
        body.put("instructions", request.systemPrompt());
        body.put("temperature", request.temperature());
        body.put("max_output_tokens", openai.getMaxOutputTokens());
        if (request.jsonObject()) {
            body.put("input", ensureJsonKeyword(request.userPrompt()));
            body.put("text", Map.of("format", Map.of("type", "json_object")));
        } else {
            body.put("input", request.userPrompt());
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
        enableStreaming(body);
        if (request.jsonObject()) {
            body.put("text", Map.of("format", Map.of(
                    "type", "json_schema",
                    "name", "reputation_content_pack",
                    "strict", true,
                    "schema", contentPackSchema()
            )));
        }

        return postResponse(body, options.timeout());
    }

    public OpenAiResponseResult createReviewTemplatesResponse(AiRequest request, String profileKey) {
        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        OpenAiContentPackOptions options = OpenAiContentPackOptions.fromProfile(openai, profileKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", options.model());
        body.put("instructions", request.systemPrompt());
        body.put("input", request.userPrompt());
        body.put("max_output_tokens", Math.min(options.maxOutputTokens(), 9000));
        if (!isBlank(options.reasoningEffort())) {
            body.put("reasoning", Map.of("effort", options.reasoningEffort()));
        }
        enableStreaming(body);
        if (request.jsonObject()) {
            body.put("text", Map.of("format", Map.of(
                    "type", "json_schema",
                    "name", "reputation_review_templates",
                    "strict", true,
                    "schema", reviewTemplatesSchema()
            )));
        }

        return postResponse(body, options.timeout());
    }

    public OpenAiResponseResult createSingleReviewDraftResponse(AiRequest request, String profileKey) {
        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        OpenAiContentPackOptions options = OpenAiContentPackOptions.fromProfile(openai, profileKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", options.model());
        body.put("instructions", request.systemPrompt());
        body.put("input", request.userPrompt());
        body.put("max_output_tokens", Math.min(options.maxOutputTokens(), 2400));
        if (!isBlank(options.reasoningEffort())) {
            body.put("reasoning", Map.of("effort", options.reasoningEffort()));
        }
        if ("reputation-single-review-draft".equals(request.task())) {
            body.put("tools", List.of(Map.of(
                    "type", "web_search_preview",
                    "search_context_size", "low"
            )));
            body.put("max_tool_calls", 2);
        }
        if (request.jsonObject()) {
            body.put("text", Map.of("format", Map.of(
                    "type", "json_schema",
                    "name", "reputation_single_review_draft",
                    "strict", true,
                    "schema", singleReviewDraftSchema()
            )));
        }

        return postResponse(body, options.timeout());
    }

    public OpenAiResponseResult createBatchReviewDraftResponse(AiRequest request, String profileKey) {
        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        OpenAiContentPackOptions options = OpenAiContentPackOptions.fromProfile(openai, profileKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", options.model());
        body.put("instructions", request.systemPrompt());
        body.put("input", request.userPrompt());
        body.put("max_output_tokens", Math.min(options.maxOutputTokens(), 9000));
        if (!isBlank(options.reasoningEffort())) {
            body.put("reasoning", Map.of("effort", options.reasoningEffort()));
        }
        if (request.jsonObject()) {
            body.put("text", Map.of("format", Map.of(
                    "type", "json_schema",
                    "name", "reputation_batch_review_drafts",
                    "strict", true,
                    "schema", batchReviewDraftSchema()
            )));
        }

        return postResponse(body, options.timeout());
    }

    public OpenAiResponseResult createBatchReviewWritingGuideResponse(AiRequest request, String profileKey) {
        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        OpenAiContentPackOptions options = OpenAiContentPackOptions.fromProfile(openai, profileKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", options.model());
        body.put("instructions", request.systemPrompt());
        body.put("input", request.userPrompt());
        body.put("max_output_tokens", Math.min(options.maxOutputTokens(), 3600));
        if (!isBlank(options.reasoningEffort())) {
            body.put("reasoning", Map.of("effort", options.reasoningEffort()));
        }
        body.put("tools", List.of(Map.of(
                "type", "web_search_preview",
                "search_context_size", "low"
        )));
        body.put("max_tool_calls", 3);
        if (request.jsonObject()) {
            body.put("text", Map.of("format", Map.of(
                    "type", "json_schema",
                    "name", "reputation_batch_review_writing_guide",
                    "strict", true,
                    "schema", batchReviewWritingGuideSchema()
            )));
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
                "schema", deepResearchSchema(12, 16)
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
        enableStreaming(body);
        body.put("text", Map.of("format", Map.of(
                "type", "json_schema",
                "name", "company_research_report",
                "strict", true,
                "schema", deepResearchSchema(options)
        )));

        return postResponse(body, options.timeout());
    }

    public OpenAiResponseResult createSourceRefreshResponse(String instructions, String input, String profileKey) {
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
        body.put("max_tool_calls", Math.max(4, Math.min(options.maxToolCalls(), 10)));
        body.put("max_output_tokens", Math.min(options.maxOutputTokens(), 6000));
        if (!isBlank(options.reasoningEffort())) {
            body.put("reasoning", Map.of("effort", options.reasoningEffort()));
        }
        body.put("text", Map.of("format", Map.of(
                "type", "json_schema",
                "name", "company_research_sources_refresh",
                "strict", true,
                "schema", sourceRefreshSchema()
        )));
        enableStreaming(body);

        return postResponse(body, options.timeout());
    }

    public OpenAiResponseResult createResearchGapEnrichmentResponse(String instructions, String input, String profileKey) {
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
        body.put("max_tool_calls", Math.max(3, Math.min(options.maxToolCalls(), 8)));
        body.put("max_output_tokens", Math.min(options.maxOutputTokens(), 7000));
        if (!isBlank(options.reasoningEffort())) {
            body.put("reasoning", Map.of("effort", options.reasoningEffort()));
        }
        body.put("text", Map.of("format", Map.of(
                "type", "json_schema",
                "name", "company_research_gap_enrichment",
                "strict", true,
                "schema", gapEnrichmentSchema()
        )));
        enableStreaming(body);

        return postResponse(body, options.timeout());
    }

    public OpenAiResponseResult createResearchReportRewriteResponse(String instructions, String input, String profileKey) {
        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        ReputationAiProperties.OpenAi.ResearchReport report = openai.getResearchReport();
        OpenAiResearchReportOptions options = OpenAiResearchReportOptions.fromProfile(report, profileKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", options.model());
        body.put("instructions", instructions);
        body.put("input", input);
        body.put("max_output_tokens", options.maxOutputTokens());
        if (!isBlank(options.reasoningEffort())) {
            body.put("reasoning", Map.of("effort", options.reasoningEffort()));
        }
        body.put("text", Map.of("format", Map.of(
                "type", "json_schema",
                "name", "company_research_report_rewrite",
                "strict", true,
                "schema", deepResearchSchema(options)
        )));
        enableStreaming(body);

        return postResponse(body, options.timeout());
    }

    public OpenAiResponseResult createResearchReportSectionRewriteResponse(String instructions, String input, String profileKey) {
        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        ReputationAiProperties.OpenAi.ResearchReport report = openai.getResearchReport();
        OpenAiResearchReportOptions options = OpenAiResearchReportOptions.fromProfile(report, profileKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", options.model());
        body.put("instructions", instructions);
        body.put("input", input);
        body.put("max_output_tokens", Math.min(options.maxOutputTokens(), 5000));
        if (!isBlank(options.reasoningEffort())) {
            body.put("reasoning", Map.of("effort", options.reasoningEffort()));
        }
        body.put("text", Map.of("format", Map.of(
                "type", "json_schema",
                "name", "company_research_section_rewrite",
                "strict", true,
                "schema", sectionRewriteSchema()
        )));
        enableStreaming(body);

        return postResponse(body, options.timeout());
    }

    private void enableStreaming(Map<String, Object> body) {
        body.remove("background");
        body.put("stream", true);
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

        Duration effectiveTimeout = effectiveTimeout(body, timeout);
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(openai.getBaseUrl() + "/responses"))
                        .version(HttpClient.Version.HTTP_1_1)
                        .timeout(effectiveTimeout)
                        .header("Authorization", "Bearer " + openai.getApiKey())
                        .header("Content-Type", "application/json")
                        .header("Accept", Boolean.TRUE.equals(body.get("stream")) ? "text/event-stream" : "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String httpError = openAiHttpError(response.statusCode(), response.body());
                    rememberCheck("http_error", response.statusCode(), httpError);
                    log.warn("OpenAI Responses API returned HTTP {} on attempt {}/{}: {}",
                            response.statusCode(),
                            attempt,
                            maxAttempts,
                            limit(response.body(), 800));
                    if (attempt < maxAttempts && isRetryableStatus(response.statusCode())) {
                        sleepBeforeRetry(response, attempt);
                        continue;
                    }
                    return errorResult("", fallbackModel, httpError);
                }

                rememberCheck("ok", response.statusCode(), "OpenAI Responses API вернул успешный ответ.");
                if (Boolean.TRUE.equals(body.get("stream"))) {
                    OpenAiResponseResult result = parseStreamResponse(response.body(), fallbackModel);
                    if (!result.errorMessage().isBlank() && attempt < maxAttempts && isRetryableOpenAiError(result.errorMessage())) {
                        sleepBeforeRetryMessage(result.errorMessage(), attempt);
                        continue;
                    }
                    return result;
                }
                JsonNode root = objectMapper.readTree(response.body());
                if (Boolean.TRUE.equals(body.get("background")) && extractOutputText(root).isBlank()) {
                    return pollResponse(root.path("id").asText(""), effectiveTimeout, fallbackModel);
                }
                return parseResponse(root, fallbackModel);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return errorResult("", fallbackModel, "Запрос OpenAI был прерван.");
            } catch (HttpTimeoutException timeoutException) {
                log.warn("OpenAI Responses API request timed out after {} on attempt {}/{}: {}",
                        effectiveTimeout,
                        attempt,
                        maxAttempts,
                        timeoutException.getMessage());
                String transportError = openAiTransportError("Запрос OpenAI", timeoutException);
                rememberCheck("network_error", null, transportError);
                return errorResult("", fallbackModel, transportError);
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
                String transportError = openAiTransportError("Запрос OpenAI", exception);
                rememberCheck("network_error", null, transportError);
                return errorResult("", fallbackModel, transportError);
            }
        }

        return errorResult("", fallbackModel, "OpenAI не вернул ответ после повторных попыток.");
    }

    private Duration effectiveTimeout(Map<String, Object> body, Duration requestedTimeout) {
        Duration base = requestedTimeout == null ? Duration.ofSeconds(60) : requestedTimeout;
        if (usesWebSearch(body) && base.compareTo(Duration.ofMinutes(8)) < 0) {
            return Duration.ofMinutes(8);
        }
        return base;
    }

    private boolean usesWebSearch(Map<String, Object> body) {
        Object tools = body.get("tools");
        if (!(tools instanceof List<?> list)) {
            return false;
        }
        for (Object tool : list) {
            if (tool instanceof Map<?, ?> map && "web_search_preview".equals(Objects.toString(map.get("type"), ""))) {
                return true;
            }
        }
        return false;
    }

    private OpenAiResponseResult parseStreamResponse(String responseBody, String fallbackModel) {
        StringBuilder outputText = new StringBuilder();
        String responseId = "";
        String model = fallbackModel;
        int inputTokens = 0;
        int outputTokens = 0;
        String errorMessage = "";

        for (String eventBlock : responseBody.split("\\R\\R")) {
            String data = streamEventData(eventBlock);
            if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
            }

            try {
                JsonNode event = objectMapper.readTree(data);
                String type = event.path("type").asText("");
                if ("response.output_text.delta".equals(type)) {
                    outputText.append(event.path("delta").asText(""));
                    continue;
                }
                if ("response.output_text.done".equals(type) && outputText.isEmpty()) {
                    outputText.append(event.path("text").asText(""));
                    continue;
                }
                if ("response.completed".equals(type)) {
                    JsonNode response = event.path("response");
                    responseId = response.path("id").asText(responseId);
                    model = response.path("model").asText(model);
                    JsonNode usage = response.path("usage");
                    inputTokens = usage.path("input_tokens").asInt(inputTokens);
                    outputTokens = usage.path("output_tokens").asInt(outputTokens);
                    if (outputText.isEmpty()) {
                        outputText.append(extractOutputText(response));
                    }
                    continue;
                }
                if ("response.failed".equals(type)
                        || "response.incomplete".equals(type)
                        || "error".equals(type)) {
                    JsonNode response = event.path("response");
                    responseId = response.path("id").asText(responseId);
                    model = response.path("model").asText(model);
                    errorMessage = firstNonBlank(
                            event.path("error").path("message").asText(""),
                            response.path("error").path("message").asText(""),
                            response.path("incomplete_details").path("reason").asText(""),
                            "OpenAI stream завершился со статусом " + type + "."
                    );
                }
            } catch (Exception exception) {
                log.debug("OpenAI Responses API stream event parse skipped: {}", exception.getMessage());
            }
        }

        String text = outputText.toString().trim();
        if (!errorMessage.isBlank()) {
            String userFacingError = openAiUserFacingError(errorMessage);
            rememberCheck("http_error", 200, userFacingError);
            return errorResult(responseId, model, userFacingError);
        }
        return new OpenAiResponseResult(responseId, text, model, inputTokens, outputTokens);
    }

    private String streamEventData(String eventBlock) {
        if (eventBlock == null || eventBlock.isBlank()) {
            return "";
        }

        StringBuilder data = new StringBuilder();
        for (String line : eventBlock.split("\\R")) {
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).trim());
            }
        }
        return data.toString().trim();
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

    private void sleepBeforeRetryMessage(String message, int attempt) throws InterruptedException {
        Thread.sleep(Duration.ofSeconds(retryAfterSeconds(message, attempt)).toMillis());
    }

    private long retryAfterSeconds(String message, int attempt) {
        String clean = message == null ? "" : message;
        Matcher matcher = RETRY_AFTER_MESSAGE.matcher(clean);
        boolean found = matcher.find();
        if (!found) {
            matcher = RETRY_AFTER_RU_MESSAGE.matcher(clean);
            found = matcher.find();
        }
        if (found) {
            try {
                return Math.max(1L, Math.min(60L, Math.round(Double.parseDouble(matcher.group(1)))));
            } catch (NumberFormatException ignored) {
                // Fall through to the exponential-ish fallback below.
            }
        }
        return Math.min(30L, 6L * attempt);
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
                        .version(HttpClient.Version.HTTP_1_1)
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + openai.getApiKey())
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String httpError = openAiHttpError(response.statusCode(), response.body());
                    rememberCheck("http_error", response.statusCode(), httpError);
                    log.warn("OpenAI Responses API poll returned HTTP {}: {}", response.statusCode(), limit(response.body(), 800));
                    return errorResult(responseId, fallbackModel, httpError);
                }

                rememberCheck("ok", response.statusCode(), "OpenAI Responses API вернул успешный фоновый ответ.");
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
                String transportError = openAiTransportError("Проверка фонового ответа OpenAI", exception);
                rememberCheck("network_error", null, transportError);
                return errorResult(responseId, fallbackModel, transportError);
            }
        }

        log.warn("OpenAI Responses API background response {} timed out", responseId);
        return errorResult(responseId, fallbackModel, "Фоновый ответ OpenAI не завершился за отведенное время.");
    }

    private OpenAiResponseResult parseResponse(JsonNode root, String fallbackModel) {
        String status = root.path("status").asText("");
        if ("failed".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status)
                || "incomplete".equalsIgnoreCase(status)) {
            return errorResult(
                    root.path("id").asText(""),
                    root.path("model").asText(fallbackModel),
                    responseErrorMessage(root, "OpenAI завершил ответ со статусом " + status + ".")
            );
        }
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

    private String responseErrorMessage(JsonNode root, String fallback) {
        return openAiUserFacingError(firstNonBlank(
                root.path("error").path("message").asText(""),
                root.path("incomplete_details").path("reason").asText(""),
                fallback
        ));
    }

    private OpenAiResponseResult errorResult(String responseId, String fallbackModel, String message) {
        return new OpenAiResponseResult(responseId, "", fallbackModel, 0, 0, limit(message, 1000));
    }

    private OpenAiLastCheck rememberCheck(String status, Integer httpStatus, String message) {
        OpenAiLastCheck check = new OpenAiLastCheck(LocalDateTime.now(), httpStatus, status, limit(message, 1000));
        lastCheck.set(check);
        return check;
    }

    private String openAiTransportError(String prefix, Exception exception) {
        String message = exception.getMessage();
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (normalized.contains("eof")
                || normalized.contains("connection refused")
                || normalized.contains("connection reset")
                || normalized.contains("connection closed")
                || normalized.contains("header parser received no bytes")
                || normalized.contains("chunked transfer encoding")
                || normalized.contains("reading_length")
                || normalized.contains("reading_data")
                || normalized.contains("remote host terminated")
                || normalized.contains("unexpected end of file")) {
            return prefix + " оборвался на сетевом уровне. Это не ошибка модели: чаще всего виноват proxy/VPN/маршрут до OpenAI. Повторите запрос или переключите локальный стенд на прямое соединение, если оно доступно.";
        }
        if (normalized.contains("timed out") || normalized.contains("timeout")) {
            return prefix + " не успел завершиться по таймауту. Повторите запрос или выберите профиль «Баланс», если mini-профиль не успевает с web search.";
        }
        return prefix + " завершился ошибкой: " + message;
    }

    private String openAiHttpError(int statusCode, String body) {
        String message = extractOpenAiErrorMessage(body);
        if (message.isBlank()) {
            message = limit(body, 800);
        }
        if (message.isBlank()) {
            message = "пустое тело ответа";
        }
        if (statusCode == 429 && isRateLimitMessage(message)) {
            return openAiRateLimitError(message);
        }
        if (statusCode == 403 && isUnsupportedRegionMessage(message)) {
            return "OpenAI отклонил запрос из неподдерживаемого региона. Проверьте, что включён OpenAI proxy: OPENAI_PROXY_ENABLED=true, заданы OPENAI_PROXY_HOST/PORT, а VPS-прокси разрешает IP приложения.";
        }
        if (statusCode == 407) {
            return "OpenAI proxy вернул HTTP 407: VPS-прокси не пустил запрос. Для схемы без логина и пароля проверьте, что Squid разрешает IP приложения через http_access allow и не требует proxy_auth.";
        }
        return "OpenAI вернул HTTP " + statusCode + ": " + message;
    }

    private boolean isRetryableOpenAiError(String message) {
        return isRateLimitMessage(message);
    }

    private String openAiUserFacingError(String message) {
        String clean = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) {
            return "";
        }
        if (isRateLimitMessage(clean)) {
            return openAiRateLimitError(clean);
        }
        if (isUnsupportedRegionMessage(clean)) {
            return "OpenAI отклонил запрос из неподдерживаемого региона. Проверьте, что включён OpenAI proxy: OPENAI_PROXY_ENABLED=true, заданы OPENAI_PROXY_HOST/PORT, а VPS-прокси разрешает IP приложения.";
        }
        if (clean.toLowerCase(Locale.ROOT).contains("proxy authentication required")) {
            return "OpenAI proxy вернул HTTP 407: VPS-прокси не пустил запрос. Для схемы без логина и пароля проверьте, что Squid разрешает IP приложения через http_access allow и не требует proxy_auth.";
        }
        return clean;
    }

    private boolean isRateLimitMessage(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("rate limit")
                || normalized.contains("rate_limit_exceeded")
                || normalized.contains("tokens per min")
                || normalized.contains("лимит токен");
    }

    private boolean isUnsupportedRegionMessage(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("unsupported_country_region_territory")
                || normalized.contains("country, region, or territory not supported")
                || normalized.contains("unsupported country")
                || normalized.contains("request_forbidden");
    }

    private String openAiRateLimitError(String message) {
        Matcher matcher = RETRY_AFTER_MESSAGE.matcher(message);
        boolean found = matcher.find();
        if (!found) {
            matcher = RETRY_AFTER_RU_MESSAGE.matcher(message);
            found = matcher.find();
        }
        String retryHint = found
                ? " API просит повторить примерно через " + matcher.group(1) + " с."
                : "";
        return "OpenAI временно упёрся в лимит токенов в минуту." + retryHint
                + " Подождите 1-2 минуты и повторите действие или выберите более лёгкий профиль.";
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

    private Map<String, Object> deepResearchSchema(OpenAiResearchReportOptions options) {
        boolean economy = "economy".equals(options.profileKey());
        return deepResearchSchema(economy ? 6 : 12, economy ? 9 : 16);
    }

    private Map<String, Object> contentPackSchema() {
        Map<String, Object> stringArray = Map.of(
                "type", "array",
                "items", Map.of("type", "string")
        );
        Map<String, Object> cappedStringArray = Map.of(
                "type", "array",
                "maxItems", 10,
                "items", Map.of("type", "string")
        );
        Map<String, Object> companyProfile = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "shortDescription", Map.of("type", "string"),
                        "category", Map.of("type", "string"),
                        "products", cappedStringArray,
                        "advantages", cappedStringArray,
                        "positiveReviewTopics", cappedStringArray,
                        "negativeReviewTopics", cappedStringArray,
                        "factualWarnings", cappedStringArray
                ),
                "required", List.of(
                        "shortDescription",
                        "category",
                        "products",
                        "advantages",
                        "positiveReviewTopics",
                        "negativeReviewTopics",
                        "factualWarnings"
                )
        );

        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "companyProfile", companyProfile,
                        "utp", cappedStringArray,
                        "adTexts", cappedStringArray,
                        "socialPostTopics", cappedStringArray,
                        "socialPosts", cappedStringArray,
                        "honestReviewTopics", cappedStringArray,
                        "reviewDraftTemplates", cappedStringArray,
                        "positiveReviewReplies", cappedStringArray,
                        "negativeReviewReplies", cappedStringArray,
                        "safetyNotes", stringArray
                ),
                "required", List.of(
                        "companyProfile",
                        "utp",
                        "adTexts",
                        "socialPostTopics",
                        "socialPosts",
                        "honestReviewTopics",
                        "reviewDraftTemplates",
                        "positiveReviewReplies",
                        "negativeReviewReplies",
                        "safetyNotes"
                )
        );
    }

    private Map<String, Object> reviewTemplatesSchema() {
        Map<String, Object> cappedStringArray = Map.of(
                "type", "array",
                "maxItems", 10,
                "items", Map.of("type", "string")
        );
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "honestReviewTopics", cappedStringArray,
                        "reviewDraftTemplates", cappedStringArray,
                        "safetyNotes", cappedStringArray
                ),
                "required", List.of("honestReviewTopics", "reviewDraftTemplates", "safetyNotes")
        );
    }

    private Map<String, Object> singleReviewDraftSchema() {
        Map<String, Object> stringArray = Map.of(
                "type", "array",
                "maxItems", 8,
                "items", Map.of("type", "string")
        );
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "idea", Map.of("type", "string"),
                        "draft", Map.of("type", "string"),
                        "sourceFacts", stringArray,
                        "safetyNotes", stringArray
                ),
                "required", List.of(
                        "idea",
                        "draft",
                        "sourceFacts",
                        "safetyNotes"
                )
        );
    }

    private Map<String, Object> batchReviewDraftSchema() {
        Map<String, Object> stringArray = Map.of(
                "type", "array",
                "maxItems", 8,
                "items", Map.of("type", "string")
        );
        Map<String, Object> draftItem = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "reviewId", Map.of("type", "integer"),
                        "idea", Map.of("type", "string"),
                        "draft", Map.of("type", "string"),
                        "sourceFacts", stringArray,
                        "safetyNotes", stringArray
                ),
                "required", List.of("reviewId", "idea", "draft", "sourceFacts", "safetyNotes")
        );
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "drafts", Map.of(
                                "type", "array",
                                "maxItems", 30,
                                "items", draftItem
                        ),
                        "safetyNotes", stringArray
                ),
                "required", List.of("drafts", "safetyNotes")
        );
    }

    private Map<String, Object> batchReviewWritingGuideSchema() {
        Map<String, Object> shortStringArray = Map.of(
                "type", "array",
                "maxItems", 8,
                "items", Map.of("type", "string")
        );
        Map<String, Object> ideaGuide = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "reviewId", Map.of("type", "integer"),
                        "angles", shortStringArray,
                        "decisionCriteria", shortStringArray,
                        "naturalDetails", shortStringArray,
                        "avoidClaims", shortStringArray
                ),
                "required", List.of("reviewId", "angles", "decisionCriteria", "naturalDetails", "avoidClaims")
        );
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "categoryLanguage", shortStringArray,
                        "termHints", shortStringArray,
                        "ideaExpansion", Map.of(
                                "type", "array",
                                "maxItems", 30,
                                "items", ideaGuide
                        ),
                        "diversityWarnings", shortStringArray,
                        "safetyNotes", shortStringArray
                ),
                "required", List.of(
                        "categoryLanguage",
                        "termHints",
                        "ideaExpansion",
                        "diversityWarnings",
                        "safetyNotes"
                )
        );
    }

    private Map<String, Object> sourceRefreshSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "sources", Map.of(
                                "type", "array",
                                "maxItems", 30,
                                "items", sourceSchema()
                        ),
                        "warnings", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string")
                        )
                ),
                "required", List.of("sources", "warnings")
        );
    }

    private Map<String, Object> sourceSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "title", Map.of("type", "string"),
                        "url", Map.of("type", "string"),
                        "type", Map.of(
                                "type", "string",
                                "enum", List.of(
                                        "official_site",
                                        "map_card",
                                        "directory",
                                        "review_platform",
                                        "social",
                                        "legal",
                                        "aggregator",
                                        "media",
                                        "other"
                                )
                        ),
                        "usedFor", Map.of(
                                "type", "array",
                                "maxItems", 8,
                                "items", Map.of("type", "string"),
                                "description", "Какие факты подтверждает источник: услуги, цены, контакты, адрес, режим, парковка, отзывы, сотрудники, юридические данные, фото, удобства."
                        ),
                        "confidence", Map.of(
                                "type", "string",
                                "enum", List.of("high", "medium", "low")
                        ),
                        "note", Map.of(
                                "type", "string",
                                "description", "Какие ключевые факты подтверждает источник: цены, адрес, правила, отзывы, акции или спорные данные."
                        )
                ),
                "required", List.of("title", "url", "type", "usedFor", "confidence", "note")
        );
    }

    private Map<String, Object> sectionRewriteSchema() {
        Map<String, Object> section = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "title", Map.of("type", "string"),
                        "body", Map.of(
                                "type", "string",
                                "description", "Переписанный markdown-фрагмент только выбранного раздела без заголовка верхнего уровня."
                        )
                ),
                "required", List.of("title", "body")
        );

        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "section", section,
                        "warnings", Map.of("type", "array", "items", Map.of("type", "string"))
                ),
                "required", List.of("section", "warnings")
        );
    }

    private Map<String, Object> gapEnrichmentSchema() {
        Map<String, Object> section = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "title", Map.of("type", "string"),
                        "body", Map.of(
                                "type", "string",
                                "description", "Markdown-раздел с ответами на пункты из блока что еще собирать: что удалось проверить публично, что не найдено и что остается уточнить у владельца."
                        )
                ),
                "required", List.of("title", "body")
        );

        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "section", section,
                        "sources", Map.of("type", "array", "items", sourceSchema()),
                        "warnings", Map.of("type", "array", "items", Map.of("type", "string"))
                ),
                "required", List.of("section", "sources", "warnings")
        );
    }

    private Map<String, Object> deepResearchSchema(int minSections, int maxSections) {
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
                                "description", "Самостоятельный markdown-фрагмент раздела. Для услуг, товаров и цен содержит таблицу с позициями, условиями, ценами и источниками, если данные найдены. Для клиентского опыта сохраняет интерьер и экстерьер филиалов, вход, этаж, ориентиры, парковку, доступность, зону ожидания, Wi-Fi, туалет, гардероб, детскую зону, онлайн-запись, доставку/самовывоз/выезд и способы оплаты, если это подтверждено источниками. Для оценки компании включает репутационные темы, доверие, возражения, сценарии, отдельные идеи для постов/карточки, отдельные 30 идей для отзывов и аудит готовности будущей карточки компании без повторов."
                        )
                ),
                "required", List.of("title", "body")
        );

        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "sections", Map.of(
                                "type", "array",
                                "description", "Тематические markdown-блоки для интерфейса и сохраненного отчёта. Держи стабильный универсальный порядок: сводка, профиль бизнеса, услуги/товары/цены, филиалы, клиентский опыт с интерьером/экстерьером/парковкой/входом/Wi-Fi/туалетом/гардеробом/детской зоной/оплатой, репутационные темы, доверие, сценарии и УТП, сотрудники, условия, сроки/аудитория, риски и возражения, что ещё собирать с аудитом готовности карточки компании, идеи для постов и карточки, идеи для отзывов, главный вывод.",
                                "minItems", minSections,
                                "maxItems", maxSections,
                                "items", section
                        ),
                        "sources", Map.of("type", "array", "items", sourceSchema()),
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String ensureJsonKeyword(String input) {
        String normalized = input == null ? "" : input.trim();
        if (normalized.toLowerCase(Locale.ROOT).contains("json")) {
            return normalized;
        }
        return "Верни результат в формате JSON.\n\n" + normalized;
    }
}
