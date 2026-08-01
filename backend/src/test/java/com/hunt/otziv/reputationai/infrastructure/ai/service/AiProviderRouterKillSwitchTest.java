package com.hunt.otziv.reputationai.infrastructure.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.reputationai.application.ReputationAiProviderSelectionService;
import com.hunt.otziv.reputationai.application.ReputationAiRuntimeSwitch;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiProviderRouterKillSwitchTest {

    @Test
    void disabledSwitchNeverDelegatesGenerationToConfiguredProvider() {
        ReputationAiProviderSelectionService selection = mock(ReputationAiProviderSelectionService.class);
        ReputationAiRuntimeSwitch runtimeSwitch = mock(ReputationAiRuntimeSwitch.class);
        AiProvider provider = mock(AiProvider.class);
        when(selection.activeProvider()).thenReturn("deepseek");
        when(runtimeSwitch.isEnabled()).thenReturn(false);
        when(provider.providerName()).thenReturn("deepseek");
        when(provider.isAvailable()).thenReturn(true);
        AiProviderRouter router = new AiProviderRouter(selection, runtimeSwitch, List.of(provider));
        AiRequest request = new AiRequest("test", "system", "user", 0.1);

        assertThat(router.activeProviderAvailable()).isFalse();
        assertThat(router.activeProviderName()).isEqualTo("deepseek");
        assertThat(router.activeProvider().generate(request).errorMessage())
                .contains("отключён оператором");
        verify(provider, never()).generate(request);
    }

    @Test
    void enabledSwitchPreservesConfiguredProvider() {
        ReputationAiProviderSelectionService selection = mock(ReputationAiProviderSelectionService.class);
        ReputationAiRuntimeSwitch runtimeSwitch = mock(ReputationAiRuntimeSwitch.class);
        AiProvider provider = mock(AiProvider.class);
        when(selection.activeProvider()).thenReturn("deepseek");
        when(runtimeSwitch.isEnabled()).thenReturn(true);
        when(provider.providerName()).thenReturn("deepseek");
        when(provider.isAvailable()).thenReturn(true);
        AiRequest request = new AiRequest("test", "system", "user", 0.1);
        AiResponse response = new AiResponse("ok", "deepseek", 1, 1, "");
        when(provider.generate(request)).thenReturn(response);
        AiProviderRouter router = new AiProviderRouter(selection, runtimeSwitch, List.of(provider));

        assertThat(router.activeProvider().generate(request)).isSameAs(response);
        assertThat(router.activeProviderAvailable()).isTrue();
    }

    @Test
    void switchFlipAfterResolutionStillBlocksGeneration() {
        ReputationAiProviderSelectionService selection = mock(ReputationAiProviderSelectionService.class);
        ReputationAiRuntimeSwitch runtimeSwitch = mock(ReputationAiRuntimeSwitch.class);
        AiProvider provider = mock(AiProvider.class);
        when(selection.activeProvider()).thenReturn("deepseek");
        when(provider.providerName()).thenReturn("deepseek");
        when(runtimeSwitch.isEnabled()).thenReturn(true, false);
        AiProviderRouter router = new AiProviderRouter(selection, runtimeSwitch, List.of(provider));
        AiRequest request = new AiRequest("test", "system", "user", 0.1);

        assertThat(router.activeProvider().generate(request).errorMessage())
                .contains("отключён оператором");
        verify(provider, never()).generate(request);
    }
}
