package com.hunt.otziv.config.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.repository.AppSettingRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AppSettingServiceFreshReadTest {

    @Test
    void safetyCriticalReadBypassesAndRefreshesTheLocalCache() {
        AppSettingRepository repository = mock(AppSettingRepository.class);
        when(repository.findById(AppSettingService.REPUTATION_AI_ENABLED))
                .thenReturn(Optional.of(com.hunt.otziv.config.settings.model.AppSetting.builder()
                        .key(AppSettingService.REPUTATION_AI_ENABLED)
                        .value("true")
                        .build()));
        when(repository.findFreshValueByKey(AppSettingService.REPUTATION_AI_ENABLED))
                .thenReturn(Optional.of("false"));

        AppSettingService service = new AppSettingService(repository);
        assertThat(service.getBoolean(AppSettingService.REPUTATION_AI_ENABLED, true)).isTrue();
        assertThat(service.getBooleanFresh(AppSettingService.REPUTATION_AI_ENABLED, true)).isFalse();
        assertThat(service.getBoolean(AppSettingService.REPUTATION_AI_ENABLED, true)).isFalse();
    }

    @Test
    void failClosedReadDefaultsOnlyMissingRowsToEnabled() {
        AppSettingRepository repository = mock(AppSettingRepository.class);
        when(repository.findFreshValueByKey(AppSettingService.REPUTATION_AI_ENABLED))
                .thenReturn(Optional.empty(), Optional.of(" definitely-on "), Optional.of(" true "));

        AppSettingService service = new AppSettingService(repository);

        assertThat(service.getBooleanFreshFailClosed(AppSettingService.REPUTATION_AI_ENABLED, true)).isTrue();
        assertThat(service.getBooleanFreshFailClosed(AppSettingService.REPUTATION_AI_ENABLED, true)).isFalse();
        assertThat(service.getBooleanFreshFailClosed(AppSettingService.REPUTATION_AI_ENABLED, true)).isTrue();
    }
}
