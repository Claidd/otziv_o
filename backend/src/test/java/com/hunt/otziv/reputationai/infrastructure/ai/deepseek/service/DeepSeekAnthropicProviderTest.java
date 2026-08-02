package com.hunt.otziv.reputationai.infrastructure.ai.deepseek.service;

import com.hunt.otziv.reputationai.infrastructure.ai.deepseek.dto.DeepSeekAnthropicResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekAnthropicProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runsFourSearchStagesAndFinalSynthesis() throws Exception {
        List<String> requestBodies = new CopyOnWriteArrayList<>();
        AtomicInteger requestNumber = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/anthropic/v1/messages", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(requestBody);
            int currentRequest = requestNumber.incrementAndGet();
            boolean webSearch = requestBody.contains("\"tools\"");
            String stageJson = "{\"stage\":\"evidence\",\"identity\":{},\"facts\":[],\"candidateSources\":[],\"unresolved\":[],\"conflicts\":[],\"warnings\":[]}";
            String text = webSearch
                    ? (currentRequest == 2
                    ? "Черновой пример {\"example\":true}. Финальный ответ:\n```json\n" + stageJson + "\n```"
                    : stageJson)
                    : "{\"sections\":[],\"sources\":["
                    + "{\"title\":\"Проверенный\",\"url\":\"https://example.org/company\"},"
                    + "{\"title\":\"Выдуманный\",\"url\":\"https://fake.example/company\"}],\"warnings\":[]}";
            String searchBlocks = webSearch ? """
                        {"type":"server_tool_use","id":"search_1","name":"web_search","input":{"query":"test"}},
                        {"type":"web_search_tool_result","tool_use_id":"search_1","content":[
                          {"type":"web_search_result","title":"Карточка","url":"https://example.org/company","encrypted_content":"encrypted"}
                        ]},
                    """ : "";
            String body = """
                    {
                      "id": "msg_%s",
                      "model": "deepseek-v4-pro",
                      "stop_reason": "end_turn",
                      "usage": {
                        "input_tokens": 100,
                        "cache_read_input_tokens": 20,
                        "output_tokens": 50,
                        "server_tool_use": {"web_search_requests": %d}
                      },
                      "content": [
                        %s
                        {"type":"text","text":%s}
                      ]
                    }
                    """.formatted(
                    currentRequest,
                    webSearch ? 2 : 0,
                    searchBlocks,
                    objectMapper.writeValueAsString(text)
            );
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            ReputationAiProperties properties = new ReputationAiProperties();
            properties.getDeepseek().setApiKey("test-key");
            properties.getDeepseek().setAnthropicBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/anthropic");
            properties.getDeepseek().setAnthropicWebSearchMaxUses(5);
            properties.getDeepseek().setAnthropicDeepSearchPasses(4);
            DeepSeekAnthropicProvider provider = new DeepSeekAnthropicProvider(properties, objectMapper);

            DeepSeekAnthropicResult result = provider.research(
                    "company-research-report",
                    "Верни JSON.",
                    "Найди компанию.",
                    4000,
                    Duration.ofSeconds(5)
            );

            assertThat(result.responseId()).isEqualTo("msg_5");
            assertThat(result.text()).contains("\"sections\"");
            assertThat(result.inputTokens()).isEqualTo(600);
            assertThat(result.outputTokens()).isEqualTo(250);
            assertThat(result.webSearchRequests()).isEqualTo(8);
            assertThat(result.webSources()).isEqualTo(4);
            assertThat(result.webSourceUrls()).containsExactly("https://example.org/company");
            assertThat(result.text())
                    .contains("https://example.org/company")
                    .doesNotContain("https://fake.example/company")
                    .contains("Удалено источников с URL");
            assertThat(result.errorMessage()).isBlank();
            assertThat(requestBodies).hasSize(5);

            JsonNode identityStage = objectMapper.readTree(requestBodies.get(0));
            assertThat(identityStage.path("model").asText()).isEqualTo("deepseek-v4-pro");
            assertThat(identityStage.path("tools").path(0).path("type").asText()).isEqualTo("web_search_20250305");
            assertThat(identityStage.path("tools").path(0).path("max_uses").asInt()).isEqualTo(5);
            assertThat(identityStage.path("messages").path(0).path("content").path(0).path("text").asText())
                    .contains("site:2gis.ru")
                    .contains("site:yandex.ru/maps")
                    .contains("site:google.com/maps")
                    .contains("site:zoon.ru");

            JsonNode synthesis = objectMapper.readTree(requestBodies.get(4));
            assertThat(synthesis.has("tools")).isFalse();
            assertThat(synthesis.path("system").asText())
                    .contains("URL в sources копируй дословно")
                    .contains("identityMatch=conflict");
            assertThat(synthesis.path("messages").path(0).path("content").path(0).path("text").asText())
                    .contains("Результаты управляемых поисковых этапов")
                    .contains("URL, реально возвращённые Web Search")
                    .contains("https://example.org/company")
                    .contains("=== identity ===")
                    .contains("=== verification ===");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void repairsMalformedStageWithoutRunningSearchAgain() throws Exception {
        List<String> requestBodies = new CopyOnWriteArrayList<>();
        AtomicInteger requestNumber = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/anthropic/v1/messages", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(requestBody);
            int currentRequest = requestNumber.incrementAndGet();
            String text = switch (currentRequest) {
                case 1 -> "Нашёл карточку, но JSON оборвался: {\"stage\":\"identity\",\"facts\":[";
                case 2 -> "{\"stage\":\"identity\",\"identity\":{},\"facts\":[],\"candidateSources\":[],\"unresolved\":[\"нет фактов\"],\"conflicts\":[],\"warnings\":[]}";
                default -> "{\"sections\":[],\"sources\":[{\"title\":\"Карточка\",\"url\":\"https://example.org/company\"}],\"warnings\":[]}";
            };
            String searchBlocks = currentRequest == 1 ? """
                    {"type":"web_search_tool_result","tool_use_id":"search_1","content":[
                      {"type":"web_search_result","title":"Карточка","url":"https://example.org/company","encrypted_content":"encrypted"}
                    ]},
                    """ : "";
            String body = """
                    {"id":"msg_%s","usage":{"input_tokens":10,"output_tokens":10,
                    "server_tool_use":{"web_search_requests":%d}},"content":[%s{"type":"text","text":%s}]}
                    """.formatted(currentRequest, currentRequest == 1 ? 1 : 0, searchBlocks, objectMapper.writeValueAsString(text));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            ReputationAiProperties properties = new ReputationAiProperties();
            properties.getDeepseek().setApiKey("test-key");
            properties.getDeepseek().setAnthropicBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/anthropic");
            properties.getDeepseek().setAnthropicDeepSearchPasses(1);
            DeepSeekAnthropicProvider provider = new DeepSeekAnthropicProvider(properties, objectMapper);

            DeepSeekAnthropicResult result = provider.research(
                    "company-research-report", "Верни JSON.", "Найди компанию.", 4000, Duration.ofSeconds(5));

            assertThat(result.errorMessage()).isBlank();
            assertThat(result.text()).contains("https://example.org/company");
            assertThat(requestBodies).hasSize(3);
            assertThat(objectMapper.readTree(requestBodies.get(0)).has("tools")).isTrue();
            assertThat(objectMapper.readTree(requestBodies.get(1)).has("tools")).isFalse();
            assertThat(objectMapper.readTree(requestBodies.get(1)).path("system").asText())
                    .contains("восстанавливаешь структуру")
                    .contains("Не добавляй фактов");
            assertThat(objectMapper.readTree(requestBodies.get(2)).has("tools")).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
