package com.hunt.otziv.admin.controller;

import com.hunt.otziv.config.settings.service.AppSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;

class ApiAdminWorkerAccountActionSettingsControllerTest {

    private final AppSettingService settings = mock(AppSettingService.class);
    private final ApiAdminWorkerAccountActionSettingsController controller =
            new ApiAdminWorkerAccountActionSettingsController(settings);

    @Test
    void bothEndpointsAreRestrictedToAdministratorsAndOwners() {
        PreAuthorize authorization = ApiAdminWorkerAccountActionSettingsController.class
                .getAnnotation(PreAuthorize.class);

        assertThat(authorization.value()).isEqualTo("hasAnyRole('ADMIN', 'OWNER')");
    }

    @Test
    void loadsCurrentDurationWithoutWaitingForSettingsCache() {
        when(settings.getStringFresh(AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_SECONDS, "60"))
                .thenReturn("180");

        assertThat(controller.getSettings().cooldownSeconds()).isEqualTo(180);
        verify(settings).getStringFresh(AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_SECONDS, "60");
        verify(settings).getStringFresh(AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_ENABLED, "true");
    }

    @Test
    void loadsDisabledFlagWithoutLosingConfiguredDuration() {
        when(settings.getStringFresh(AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_SECONDS, "60"))
                .thenReturn("180");
        when(settings.getStringFresh(AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_ENABLED, "true"))
                .thenReturn(" false ");

        var response = controller.getSettings();

        assertThat(response.enabled()).isFalse();
        assertThat(response.cooldownSeconds()).isEqualTo(180);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savesIndependentSwitchAndDurationTogether(boolean enabled) throws Exception {
        when(settings.setInt(AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_SECONDS, 180)).thenReturn(180);

        var response = controller.updateSettings(
                new ApiAdminWorkerAccountActionSettingsController.WorkerAccountActionSettingsRequest(
                        enabled, BigDecimal.valueOf(180)));

        assertThat(response.enabled()).isEqualTo(enabled);
        assertThat(response.cooldownSeconds()).isEqualTo(180);
        verify(settings).setBoolean(AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_ENABLED, enabled);
        verify(settings).setInt(AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_SECONDS, 180);
        assertThat(ApiAdminWorkerAccountActionSettingsController.class.getMethod("updateSettings",
                ApiAdminWorkerAccountActionSettingsController.WorkerAccountActionSettingsRequest.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void durationOnlyLegacyRequestPreservesCurrentSwitch(boolean enabled) {
        when(settings.getStringFresh(AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_ENABLED, "true"))
                .thenReturn(String.valueOf(enabled));
        when(settings.setInt(AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_SECONDS, 120)).thenReturn(120);

        var response = controller.updateSettings(
                new ApiAdminWorkerAccountActionSettingsController.WorkerAccountActionSettingsRequest(
                        BigDecimal.valueOf(120)));

        assertThat(response.enabled()).isEqualTo(enabled);
        assertThat(response.cooldownSeconds()).isEqualTo(120);
        verify(settings, never()).setBoolean(anyString(), anyBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "-1", "3601", "0.5", "999999999999999999999"})
    void malformedStoredDurationUsesDefaultMinute(String configured) {
        when(settings.getStringFresh(AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_SECONDS, "60"))
                .thenReturn(configured);

        assertThat(controller.getSettings().cooldownSeconds()).isEqualTo(60);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 60, 180, 3600})
    void savesValidDurationIncludingDisableAndMaximum(int seconds) {
        when(settings.setInt(AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_SECONDS, seconds))
                .thenReturn(seconds);

        var response = controller.updateSettings(
                new ApiAdminWorkerAccountActionSettingsController.WorkerAccountActionSettingsRequest(
                        BigDecimal.valueOf(seconds)
                )
        );

        assertThat(response.cooldownSeconds()).isEqualTo(seconds);
        verify(settings).setInt(AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_SECONDS, seconds);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "3601", "0.5", "60.25", "999999999999999999999"})
    void rejectsOutOfRangeAndFractionalDurationWithoutPersisting(String seconds) {
        assertThatThrownBy(() -> controller.updateSettings(
                new ApiAdminWorkerAccountActionSettingsController.WorkerAccountActionSettingsRequest(
                        new BigDecimal(seconds)
                )
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(settings);
    }

    @Test
    void rejectsMissingDurationWithoutPersisting() {
        assertThatThrownBy(() -> controller.updateSettings(
                new ApiAdminWorkerAccountActionSettingsController.WorkerAccountActionSettingsRequest(null)
        )).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.updateSettings(null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(settings);
    }
}
