package com.hunt.otziv.reputationai.infrastructure.ai.deepseek.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsJsonThinkingRequestAndParsesChatCompletion() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = completionServer(requestBody, authorization, """
                {
                  "id": "chat_test",
                  "model": "deepseek-v4-pro",
                  "choices": [
                    {
                      "finish_reason": "stop",
                      "message": {
                        "role": "assistant",
                        "reasoning_content": "internal reasoning",
                        "content": "{\\\"drafts\\\":[]}"
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 17,
                    "completion_tokens": 23
                  }
                }
                """);

        server.start();
        try {
            DeepSeekProvider provider = provider(server);
            AiResponse response = provider.generate(new AiRequest(
                    "review-drafts",
                    "Верни валидный JSON.",
                    "Подготовь отзывы.",
                    0.7,
                    true,
                    9000,
                    Duration.ofSeconds(5)
            ));

            assertThat(response.text()).isEqualTo("{\"drafts\":[]}");
            assertThat(response.provider()).isEqualTo("deepseek");
            assertThat(response.inputTokens()).isEqualTo(17);
            assertThat(response.outputTokens()).isEqualTo(23);
            assertThat(response.errorMessage()).isBlank();
            assertThat(authorization.get()).isEqualTo("Bearer test-deepseek-key");

            JsonNode sent = objectMapper.readTree(requestBody.get());
            assertThat(sent.path("model").asText()).isEqualTo("deepseek-v4-pro");
            assertThat(sent.path("max_tokens").asInt()).isEqualTo(9000);
            assertThat(sent.path("thinking").path("type").asText()).isEqualTo("enabled");
            assertThat(sent.path("reasoning_effort").asText()).isEqualTo("high");
            assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(sent.has("temperature")).isFalse();
            assertThat(sent.path("messages").path(0).path("role").asText()).isEqualTo("system");
            assertThat(sent.path("messages").path(1).path("role").asText()).isEqualTo("user");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsApiErrorMessage() throws Exception {
        HttpServer server = completionServer(new AtomicReference<>(), new AtomicReference<>(), """
                {"error":{"message":"Insufficient Balance"}}
                """, 402);

        server.start();
        try {
            AiResponse response = provider(server).generate(new AiRequest(
                    "test", "system", "user", 0.0, false
            ));

            assertThat(response.text()).isBlank();
            assertThat(response.errorMessage()).contains("HTTP 402").contains("Insufficient Balance");
        } finally {
            server.stop(0);
        }
    }

    private DeepSeekProvider provider(HttpServer server) {
        ReputationAiProperties properties = new ReputationAiProperties();
        properties.getDeepseek().setApiKey("test-deepseek-key");
        properties.getDeepseek().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getDeepseek().setModel("deepseek-v4-pro");
        properties.getDeepseek().setTimeout(Duration.ofSeconds(5));
        properties.getDeepseek().setMaxTokens(24000);
        properties.getDeepseek().setThinkingEnabled(true);
        properties.getDeepseek().setReasoningEffort("high");
        return new DeepSeekProvider(properties, objectMapper);
    }

    private HttpServer completionServer(
            AtomicReference<String> requestBody,
            AtomicReference<String> authorization,
            String responseBody
    ) throws Exception {
        return completionServer(requestBody, authorization, responseBody, 200);
    }

    private HttpServer completionServer(
            AtomicReference<String> requestBody,
            AtomicReference<String> authorization,
            String responseBody,
            int status
    ) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return server;
    }
}
