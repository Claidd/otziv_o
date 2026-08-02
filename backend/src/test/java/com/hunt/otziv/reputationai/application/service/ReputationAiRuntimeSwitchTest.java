package com.hunt.otziv.reputationai.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import org.junit.jupiter.api.Test;

class ReputationAiRuntimeSwitchTest {

    @Test
    void defaultsToEnabledForBackwardCompatibility() {
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getBooleanFreshFailClosed(AppSettingService.REPUTATION_AI_ENABLED, true)).thenReturn(true);

        assertThat(new ReputationAiRuntimeSwitch(settings).isEnabled()).isTrue();
        verify(settings).getBooleanFreshFailClosed(AppSettingService.REPUTATION_AI_ENABLED, true);
    }

    @Test
    void writesThroughAppSettingServiceForImmediateCacheRefresh() {
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.setBoolean(AppSettingService.REPUTATION_AI_ENABLED, false)).thenReturn(false);
        ReputationAiRuntimeSwitch runtimeSwitch = new ReputationAiRuntimeSwitch(settings);

        assertThat(runtimeSwitch.setEnabled(false)).isFalse();
        verify(settings).setBoolean(AppSettingService.REPUTATION_AI_ENABLED, false);
    }

    @Test
    void settingsReadFailureFailsClosedWithoutPropagatingDatabaseDetails() {
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getBooleanFreshFailClosed(AppSettingService.REPUTATION_AI_ENABLED, true))
                .thenThrow(new IllegalStateException("jdbc:secret-host"));

        assertThat(new ReputationAiRuntimeSwitch(settings).isEnabled()).isFalse();
    }
}
