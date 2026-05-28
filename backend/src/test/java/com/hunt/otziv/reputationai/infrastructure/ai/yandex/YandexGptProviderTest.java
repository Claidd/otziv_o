package com.hunt.otziv.reputationai.infrastructure.ai.yandex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.AiResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class YandexGptProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesCompletionResponseFromResultEnvelope() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = completionServer(requestBody, """
                {
                  "result": {
                    "alternatives": [
                      {
                        "message": {
                          "role": "assistant",
                          "text": "готовый текст"
                        },
                        "status": "ALTERNATIVE_STATUS_FINAL"
                      }
                    ],
                    "usage": {
                      "inputTextTokens": "12",
                      "completionTokens": "34"
                    }
                  }
                }
                """);

        server.start();
        try {
            YandexGptProvider provider = provider(server);

            AiResponse response = provider.generate(new AiRequest(
                    "test",
                    "Системная инструкция.",
                    "Пользовательский запрос.",
                    0.2,
                    true,
                    123,
                    Duration.ofSeconds(5)
            ));

            assertThat(response.text()).isEqualTo("готовый текст");
            assertThat(response.provider()).isEqualTo("yandexgpt");
            assertThat(response.inputTokens()).isEqualTo(12);
            assertThat(response.outputTokens()).isEqualTo(34);
            assertThat(response.errorMessage()).isBlank();

            JsonNode sent = objectMapper.readTree(requestBody.get());
            assertThat(sent.path("modelUri").asText()).isEqualTo("gpt://test-folder/yandexgpt-lite");
            assertThat(sent.path("completionOptions").path("maxTokens").asText()).isEqualTo("123");
            assertThat(sent.path("messages").path(0).path("text").asText()).contains("валидный JSON-объект");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsDiagnosticWhenSuccessfulResponseHasNoText() throws Exception {
        HttpServer server = completionServer(new AtomicReference<>(), """
                {
                  "result": {
                    "alternatives": [
                      {
                        "message": {"role": "assistant", "text": ""},
                        "status": "ALTERNATIVE_STATUS_CONTENT_FILTER"
                      }
                    ],
                    "usage": {
                      "inputTextTokens": "5",
                      "completionTokens": "0"
                    }
                  }
                }
                """);

        server.start();
        try {
            AiResponse response = provider(server).generate(new AiRequest(
                    "test",
                    "system",
                    "user",
                    0.0,
                    false
            ));

            assertThat(response.text()).isBlank();
            assertThat(response.errorMessage())
                    .contains("YandexGPT вернул пустой текст")
                    .contains("ALTERNATIVE_STATUS_CONTENT_FILTER");
            assertThat(response.inputTokens()).isEqualTo(5);
            assertThat(response.outputTokens()).isZero();
        } finally {
            server.stop(0);
        }
    }

    private YandexGptProvider provider(HttpServer server) {
        ReputationAiProperties properties = new ReputationAiProperties();
        properties.getYandex().setApiKey("test-key");
        properties.getYandex().setFolderId("test-folder");
        properties.getYandex().setModel("yandexgpt-lite");
        properties.getYandex().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/completion");
        return new YandexGptProvider(properties, objectMapper);
    }

    private HttpServer completionServer(AtomicReference<String> requestBody, String responseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/completion", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return server;
    }
}
