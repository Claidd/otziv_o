package com.hunt.otziv.reputationai.application;

import com.hunt.otziv.config.settings.service.AppSettingService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReputationAiProviderSelectionServiceTest {

    private final AppSettingService appSettingService = mock(AppSettingService.class);
    private final ReputationAiProviderSelectionService service = new ReputationAiProviderSelectionService(appSettingService);

    @Test
    void defaultsToDeepSeek() {
        when(appSettingService.getString(
                ReputationAiProviderSelectionService.SETTING_KEY,
                ReputationAiProviderSelectionService.DEFAULT_PROVIDER
        )).thenReturn("deepseek");

        assertThat(service.activeProvider()).isEqualTo("deepseek");
    }

    @Test
    void normalizesAndPersistsProvider() {
        assertThat(service.select("Yandex-GPT")).isEqualTo("yandexgpt");

        verify(appSettingService).setString(ReputationAiProviderSelectionService.SETTING_KEY, "yandexgpt");
    }

    @Test
    void rejectsUnsupportedProvider() {
        assertThatThrownBy(() -> service.select("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported AI provider");
    }
}
