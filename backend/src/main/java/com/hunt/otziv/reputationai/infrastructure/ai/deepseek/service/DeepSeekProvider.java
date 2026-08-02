package com.hunt.otziv.reputationai.infrastructure.ai.deepseek.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class DeepSeekProvider implements AiProvider {

    private final ReputationAiProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();

    @Override
    public AiResponse generate(AiRequest request) {
        if (!isAvailable()) {
            return new AiResponse(
                    "",
                    providerName(),
                    0,
                    0,
                    "DeepSeek не настроен: укажите DEEPSEEK_API_KEY."
            );
        }

        ReputationAiProperties.DeepSeek deepSeek = properties.getDeepseek();
        try {
            String requestJson = objectMapper.writeValueAsString(buildRequestBody(request, deepSeek));
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(deepSeek.getBaseUrl() + "/chat/completions"))
                    .timeout(request.timeout() == null ? deepSeek.getTimeout() : request.timeout())
                    .header("Authorization", "Bearer " + deepSeek.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = deepSeekError(response.body());
                log.warn("DeepSeek returned HTTP {}: {}", response.statusCode(), limit(message, 300));
                return new AiResponse(
                        "",
                        providerName(),
                        0,
                        0,
                        "DeepSeek вернул HTTP " + response.statusCode() + ": " + limit(message, 700)
                );
            }

            JsonNode root = objectMapper.readTree(response.body());
            String text = root.path("choices").path(0).path("message").path("content").asText("");
            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("prompt_tokens").asInt(0);
            int outputTokens = usage.path("completion_tokens").asInt(0);
            if (text.isBlank()) {
                String finishReason = root.path("choices").path(0).path("finish_reason").asText("");
                return new AiResponse(
                        "",
                        providerName(),
                        inputTokens,
                        outputTokens,
                        "DeepSeek вернул пустой текст"
                                + (finishReason.isBlank() ? "." : ", finish_reason=" + finishReason + ".")
                );
            }
            return new AiResponse(text, providerName(), inputTokens, outputTokens);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return new AiResponse("", providerName(), 0, 0, "Запрос DeepSeek был прерван.");
        } catch (Exception exception) {
            log.warn("DeepSeek request failed: {}", exception.getMessage());
            return new AiResponse(
                    "",
                    providerName(),
                    0,
                    0,
                    "Запрос DeepSeek не выполнен: " + exception.getMessage()
            );
        }
    }

    @Override
    public String providerName() {
        return "deepseek";
    }

    @Override
    public boolean isAvailable() {
        ReputationAiProperties.DeepSeek deepSeek = properties.getDeepseek();
        return !deepSeek.getApiKey().isBlank()
                && !deepSeek.getBaseUrl().isBlank()
                && !deepSeek.getModel().isBlank();
    }

    private Map<String, Object> buildRequestBody(
            AiRequest request,
            ReputationAiProperties.DeepSeek deepSeek
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", deepSeek.getModel());
        body.put("messages", messages(request));
        body.put("stream", false);
        body.put("max_tokens", maxTokens(request, deepSeek));
        boolean thinkingEnabled = request.thinkingEnabled() == null
                ? deepSeek.isThinkingEnabled()
                : request.thinkingEnabled();
        body.put("thinking", Map.of("type", thinkingEnabled ? "enabled" : "disabled"));
        if (thinkingEnabled) {
            body.put("reasoning_effort", deepSeek.getReasoningEffort());
        } else {
            body.put("temperature", request.temperature());
        }
        if (Boolean.TRUE.equals(request.jsonObject())) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return body;
    }

    private List<Map<String, String>> messages(AiRequest request) {
        List<Map<String, String>> messages = new ArrayList<>();
        String systemPrompt = request.systemPrompt();
        if (Boolean.TRUE.equals(request.jsonObject())
                && !systemPrompt.toLowerCase().contains("json")) {
            systemPrompt = systemPrompt + "\nВерни результат как валидный JSON-объект.";
        }
        if (!systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", request.userPrompt()));
        return messages;
    }

    private int maxTokens(AiRequest request, ReputationAiProperties.DeepSeek deepSeek) {
        int desired = request.maxTokens() == null ? deepSeek.getMaxTokens() : request.maxTokens();
        return Math.max(1, Math.min(desired, deepSeek.getMaxTokens()));
    }

    private String deepSeekError(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String message = root.path("error").path("message").asText("");
            return message.isBlank() ? responseBody : message;
        } catch (Exception ignored) {
            return responseBody;
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "пустой ответ";
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength).trim();
    }
}
