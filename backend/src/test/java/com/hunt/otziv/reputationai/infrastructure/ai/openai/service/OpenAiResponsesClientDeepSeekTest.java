package com.hunt.otziv.reputationai.infrastructure.ai.openai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.application.ReputationAiProviderSelectionService;
import com.hunt.otziv.reputationai.application.ReputationAiRuntimeSwitch;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.deepseek.DeepSeekAnthropicResult;
import com.hunt.otziv.reputationai.infrastructure.ai.deepseek.DeepSeekProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.deepseek.DeepSeekAnthropicProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.dto.OpenAiResponseResult;
import com.hunt.otziv.reputationai.infrastructure.ai.yandex.YandexGptProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiResponsesClientDeepSeekTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void routesResearchReportToDeepSeekFallbackWithHybridSearchContext() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = """
                    {
                      "choices": [{
                        "finish_reason": "stop",
                        "message": {
                          "content": "{\\\"sections\\\":[],\\\"sources\\\":[],\\\"warnings\\\":[]}"
                        }
                      }],
                      "usage": {"prompt_tokens": 11, "completion_tokens": 22}
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.start();
        try {
            ReputationAiProperties properties = new ReputationAiProperties();
            properties.setProvider("deepseek");
            properties.getDeepseek().setApiKey("test-key");
            properties.getDeepseek().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getDeepseek().setModel("deepseek-v4-pro");
            properties.getDeepseek().setTimeout(Duration.ofSeconds(5));

            DeepSeekProvider deepSeekProvider = new DeepSeekProvider(properties, objectMapper);
            ReputationAiProviderSelectionService providerSelectionService = mock(ReputationAiProviderSelectionService.class);
            when(providerSelectionService.activeProvider()).thenReturn("deepseek");
            ReputationAiRuntimeSwitch runtimeSwitch = mock(ReputationAiRuntimeSwitch.class);
            when(runtimeSwitch.isEnabled()).thenReturn(true);
            OpenAiResponsesClient client = new OpenAiResponsesClient(
                    properties,
                    providerSelectionService,
                    objectMapper,
                    new YandexGptProvider(properties, objectMapper),
                    deepSeekProvider,
                    mock(DeepSeekAnthropicProvider.class),
                    runtimeSwitch
            );

            OpenAiResponseResult result = client.createResearchReportResponse(
                    "Собери подробный отчёт и верни JSON.",
                    "CRM, ручные URL и сохранённые источники.",
                    "maximum"
            );

            assertThat(client.activeProviderName()).isEqualTo("deepseek");
            assertThat(client.activeModel()).isEqualTo("deepseek-v4-pro");
            assertThat(client.usesExternalSearchContext()).isTrue();
            assertThat(result.provider()).isEqualTo("deepseek");
            assertThat(result.model()).isEqualTo("deepseek-v4-pro");
            assertThat(result.text()).contains("\"sections\"");
            assertThat(result.inputTokens()).isEqualTo(11);
            assertThat(result.outputTokens()).isEqualTo(22);

            JsonNode sent = objectMapper.readTree(requestBody.get());
            assertThat(sent.path("model").asText()).isEqualTo("deepseek-v4-pro");
            assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(sent.path("messages").path(0).path("content").asText())
                    .contains("не выполняет встроенный живой веб-поиск")
                    .doesNotContain("Yandex Search");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void routesResearchReportToDeepSeekAnthropicWebSearch() {
        ReputationAiProperties properties = new ReputationAiProperties();
        properties.getDeepseek().setApiKey("test-key");
        properties.getDeepseek().setModel("deepseek-v4-pro");

        ReputationAiProviderSelectionService providerSelectionService = mock(ReputationAiProviderSelectionService.class);
        when(providerSelectionService.activeProvider()).thenReturn("deepseek");
        DeepSeekAnthropicProvider anthropicProvider = mock(DeepSeekAnthropicProvider.class);
        when(anthropicProvider.isAvailable()).thenReturn(true);
        when(anthropicProvider.research(any(), any(), any(), anyInt(), any()))
                .thenReturn(new DeepSeekAnthropicResult(
                        "msg_web_search",
                        "```json\n{\"sections\":[],\"sources\":[],\"warnings\":[]}\n```",
                        120,
                        45,
                        3,
                        2,
                        List.of("https://example.org/company"),
                        ""
                ));

        ReputationAiRuntimeSwitch runtimeSwitch = mock(ReputationAiRuntimeSwitch.class);
        when(runtimeSwitch.isEnabled()).thenReturn(true);
        OpenAiResponsesClient client = new OpenAiResponsesClient(
                properties,
                providerSelectionService,
                objectMapper,
                new YandexGptProvider(properties, objectMapper),
                mock(DeepSeekProvider.class),
                anthropicProvider,
                runtimeSwitch
        );

        OpenAiResponseResult result = client.createResearchReportResponse(
                "Собери отчёт.",
                "CRM-факты.",
                "maximum"
        );

        assertThat(client.usesExternalSearchContext()).isTrue();
        assertThat(result.responseId()).isEqualTo("msg_web_search");
        assertThat(result.text()).isEqualTo("{\"sections\":[],\"sources\":[],\"warnings\":[]}");
        assertThat(result.inputTokens()).isEqualTo(120);
        assertThat(result.outputTokens()).isEqualTo(45);
    }
}
