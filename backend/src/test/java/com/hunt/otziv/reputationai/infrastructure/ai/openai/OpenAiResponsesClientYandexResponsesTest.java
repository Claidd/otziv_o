package com.hunt.otziv.reputationai.infrastructure.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.yandex.YandexGptProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiResponsesClientYandexResponsesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsDeepReportThroughYandexResponsesWebSearchWithStrictSchema() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = responsesServer(requestBody);

        server.start();
        try {
            OpenAiResponsesClient client = client(server);

            OpenAiResponseResult result = client.createResearchReportResponse(
                    "Собери глубокий отчет.",
                    "Компания: тестовая.\nВерни JSON.",
                    "maximum"
            );

            assertThat(result.errorMessage()).isBlank();
            assertThat(result.provider()).isEqualTo("yandexgpt");
            assertThat(result.text()).contains("\"sections\"");

            JsonNode sent = objectMapper.readTree(requestBody.get());
            assertThat(sent.path("model").asText()).isEqualTo("gpt://test-folder/yandexgpt/rc");
            assertThat(sent.path("max_output_tokens").asInt()).isEqualTo(16000);
            assertThat(sent.path("tools").path(0).path("type").asText()).isEqualTo("web_search");
            assertThat(sent.path("tools").path(0).path("search_context_size").asText()).isEqualTo("high");
            assertThat(sent.path("tool_choice").asText()).isEqualTo("auto");
            assertThat(sent.path("max_tool_calls").asInt()).isEqualTo(24);
            assertThat(sent.path("text").path("format").path("type").asText()).isEqualTo("json_schema");
            assertThat(sent.path("text").path("format").path("name").asText()).isEqualTo("company_research_report");
            assertThat(sent.path("text").path("format").path("strict").asBoolean()).isTrue();
            assertThat(sent.path("instructions").asText()).contains("web_search");
        } finally {
            server.stop(0);
        }
    }

    private OpenAiResponsesClient client(HttpServer server) {
        ReputationAiProperties properties = new ReputationAiProperties();
        properties.setProvider("yandexgpt");
        properties.getYandex().setApiKey("test-key");
        properties.getYandex().setFolderId("test-folder");
        properties.getYandex().setApiMode("responses");
        properties.getYandex().setResponsesBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getYandex().setModel("yandexgpt/rc");
        properties.getYandex().setTimeout(Duration.ofSeconds(5));
        properties.getYandex().setMaxTokens(16000);
        properties.getYandex().setMaxToolCalls(24);
        properties.getYandex().setSearchContextSize("high");

        YandexGptProvider yandexGptProvider = new YandexGptProvider(properties, objectMapper);
        return new OpenAiResponsesClient(properties, objectMapper, yandexGptProvider);
    }

    private HttpServer responsesServer(AtomicReference<String> requestBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = """
                    {
                      "id": "resp_test",
                      "status": "completed",
                      "model": "gpt://test-folder/yandexgpt/rc",
                      "output_text": "{\\"sections\\":[],\\"sources\\":[],\\"warnings\\":[]}",
                      "usage": {
                        "input_tokens": 11,
                        "output_tokens": 22
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return server;
    }
}
