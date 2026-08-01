package com.hunt.otziv.reputationai.infrastructure.ai.openai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.application.ReputationAiProviderSelectionService;
import com.hunt.otziv.reputationai.application.ReputationAiRuntimeSwitch;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.deepseek.DeepSeekAnthropicProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.deepseek.DeepSeekProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.dto.OpenAiResponseResult;
import com.hunt.otziv.reputationai.infrastructure.ai.yandex.YandexGptProvider;
import org.junit.jupiter.api.Test;

class OpenAiResponsesClientKillSwitchTest {

    @Test
    void disabledSwitchBlocksDirectClientAndConnectionCheck() {
        ReputationAiProperties properties = new ReputationAiProperties();
        ReputationAiProviderSelectionService selection = mock(ReputationAiProviderSelectionService.class);
        YandexGptProvider yandex = mock(YandexGptProvider.class);
        DeepSeekProvider deepSeek = mock(DeepSeekProvider.class);
        DeepSeekAnthropicProvider anthropic = mock(DeepSeekAnthropicProvider.class);
        ReputationAiRuntimeSwitch runtimeSwitch = mock(ReputationAiRuntimeSwitch.class);
        when(selection.activeProvider()).thenReturn("deepseek");
        when(runtimeSwitch.isEnabled()).thenReturn(false);
        OpenAiResponsesClient client = new OpenAiResponsesClient(
                properties,
                selection,
                new ObjectMapper(),
                yandex,
                deepSeek,
                anthropic,
                runtimeSwitch
        );

        OpenAiResponseResult result = client.createTextResponse(
                new AiRequest("test", "system", "user", 0.1)
        );

        assertThat(client.isAvailable()).isFalse();
        assertThat(result.text()).isEmpty();
        assertThat(result.errorMessage()).contains("отключён оператором");
        assertThat(client.checkConnection().status()).isEqualTo("disabled");
        verifyNoInteractions(yandex, deepSeek, anthropic);
    }

    @Test
    void missingRuntimeSwitchFailsClosedForManualConstruction() {
        ReputationAiProperties properties = new ReputationAiProperties();
        ReputationAiProviderSelectionService selection = mock(ReputationAiProviderSelectionService.class);
        YandexGptProvider yandex = mock(YandexGptProvider.class);
        DeepSeekProvider deepSeek = mock(DeepSeekProvider.class);
        DeepSeekAnthropicProvider anthropic = mock(DeepSeekAnthropicProvider.class);
        OpenAiResponsesClient client = new OpenAiResponsesClient(
                properties,
                selection,
                new ObjectMapper(),
                yandex,
                deepSeek,
                anthropic
        );

        OpenAiResponseResult result = client.createTextResponse(
                new AiRequest("test", "system", "user", 0.1)
        );

        assertThat(client.isAvailable()).isFalse();
        assertThat(result.errorMessage()).contains("отключён оператором");
        verifyNoInteractions(selection, yandex, deepSeek, anthropic);
    }
}
