package com.hunt.otziv.reputationai.infrastructure.ai.yandex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.AiProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.AiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class YandexGptProvider implements AiProvider {

    private final ReputationAiProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    @Override
    public AiResponse generate(AiRequest request) {
        if (!isAvailable()) {
            return new AiResponse("", providerName(), 0, 0);
        }

        ReputationAiProperties.YandexGpt yandex = properties.getYandex();
        try {
            String requestJson = objectMapper.writeValueAsString(buildRequestBody(request, yandex));
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(yandex.getBaseUrl()))
                    .timeout(request.timeout() == null ? yandex.getTimeout() : request.timeout())
                    .header("Authorization", "Api-Key " + yandex.getApiKey())
                    .header("x-folder-id", yandex.getFolderId())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("YandexGPT returned HTTP {}", response.statusCode());
                return new AiResponse(
                        "",
                        providerName(),
                        0,
                        0,
                        "YandexGPT вернул HTTP " + response.statusCode() + ": " + limit(response.body(), 500)
                );
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode result = root.has("result") ? root.path("result") : root;
            JsonNode alternative = result.path("alternatives").path(0);
            String text = firstNonBlank(
                    alternative.path("message").path("text").asText(""),
                    alternative.path("text").asText("")
            );
            JsonNode usage = result.path("usage");
            int inputTokens = parseInt(firstNonBlank(
                    usage.path("inputTextTokens").asText(""),
                    usage.path("inputTokens").asText(""),
                    usage.path("promptTokens").asText("")
            ));
            int outputTokens = parseInt(firstNonBlank(
                    usage.path("completionTokens").asText(""),
                    usage.path("outputTextTokens").asText(""),
                    usage.path("outputTokens").asText("")
            ));
            if (text.isBlank()) {
                String status = firstNonBlank(
                        alternative.path("status").asText(""),
                        result.path("status").asText(""),
                        root.path("error").path("message").asText(""),
                        root.path("message").asText("")
                );
                String detail = status.isBlank() ? "" : " status=" + status + ".";
                return new AiResponse(
                        "",
                        providerName(),
                        inputTokens,
                        outputTokens,
                        "YandexGPT вернул пустой текст." + detail + " Ответ: " + limit(response.body(), 900)
                );
            }
            return new AiResponse(
                    text,
                    providerName(),
                    inputTokens,
                    outputTokens
            );
        } catch (Exception exception) {
            log.warn("YandexGPT request failed: {}", exception.getMessage());
            return new AiResponse("", providerName(), 0, 0, "Запрос YandexGPT не выполнен: " + exception.getMessage());
        }
    }

    @Override
    public String providerName() {
        return "yandexgpt";
    }

    @Override
    public boolean isAvailable() {
        ReputationAiProperties.YandexGpt yandex = properties.getYandex();
        return !isBlank(yandex.getApiKey()) && !isBlank(yandex.getFolderId()) && !isBlank(yandex.getModel());
    }

    private Map<String, Object> buildRequestBody(AiRequest request, ReputationAiProperties.YandexGpt yandex) {
        Map<String, Object> completionOptions = new LinkedHashMap<>();
        completionOptions.put("stream", false);
        completionOptions.put("temperature", request.temperature());
        completionOptions.put("maxTokens", String.valueOf(maxTokens(request, yandex)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("modelUri", modelUri(yandex));
        body.put("completionOptions", completionOptions);
        body.put("messages", List.of(
                Map.of("role", "system", "text", systemPrompt(request)),
                Map.of("role", "user", "text", request.userPrompt())
        ));
        return body;
    }

    private String systemPrompt(AiRequest request) {
        if (!request.jsonObject()) {
            return request.systemPrompt();
        }
        return request.systemPrompt() + "\n\n"
                + "Верни только один валидный JSON-объект без markdown, без ```json, без пояснений до или после JSON.";
    }

    private int maxTokens(AiRequest request, ReputationAiProperties.YandexGpt yandex) {
        return request.maxTokens() == null ? yandex.getMaxTokens() : Math.max(1, request.maxTokens());
    }

    private String modelUri(ReputationAiProperties.YandexGpt yandex) {
        String model = yandex.getModel();
        if (model.startsWith("gpt://")) {
            return model;
        }

        return "gpt://" + yandex.getFolderId() + "/" + model;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception exception) {
            return 0;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength).trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
