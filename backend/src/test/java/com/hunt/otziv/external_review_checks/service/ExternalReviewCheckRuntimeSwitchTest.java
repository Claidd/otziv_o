package com.hunt.otziv.external_review_checks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
import org.junit.jupiter.api.Test;

class ExternalReviewCheckRuntimeSwitchTest {

    @Test
    void deploymentPropertyIsAHardMasterAndAvoidsDatabaseReadWhenOff() {
        ExternalReviewCheckProperties properties = new ExternalReviewCheckProperties();
        properties.setEnabled(false);
        AppSettingService settings = mock(AppSettingService.class);

        assertThat(new ExternalReviewCheckRuntimeSwitch(properties, settings).isEnabled()).isFalse();
        verify(settings, never()).getBooleanFreshFailClosed(
                AppSettingService.EXTERNAL_REVIEW_CHECK_ENABLED,
                true
        );
    }

    @Test
    void enabledMasterUsesFreshOperatorSetting() {
        ExternalReviewCheckProperties properties = new ExternalReviewCheckProperties();
        properties.setEnabled(true);
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getBooleanFreshFailClosed(
                AppSettingService.EXTERNAL_REVIEW_CHECK_ENABLED,
                true
        )).thenReturn(false, true);
        ExternalReviewCheckRuntimeSwitch runtimeSwitch =
                new ExternalReviewCheckRuntimeSwitch(properties, settings);

        assertThat(runtimeSwitch.isEnabled()).isFalse();
        assertThat(runtimeSwitch.isEnabled()).isTrue();
        verify(settings, org.mockito.Mockito.times(2)).getBooleanFreshFailClosed(
                AppSettingService.EXTERNAL_REVIEW_CHECK_ENABLED,
                true
        );
    }

    @Test
    void databaseReadFailureFailsClosedWithoutPropagatingRawFailure() {
        ExternalReviewCheckProperties properties = new ExternalReviewCheckProperties();
        properties.setEnabled(true);
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getBooleanFreshFailClosed(
                AppSettingService.EXTERNAL_REVIEW_CHECK_ENABLED,
                true
        )).thenThrow(new IllegalStateException("jdbc:secret@database"));

        assertThat(new ExternalReviewCheckRuntimeSwitch(properties, settings).isEnabled()).isFalse();
    }

    @Test
    void operatorUpdateIsWrittenThenReportedWithEffectiveMasterState() {
        ExternalReviewCheckProperties properties = new ExternalReviewCheckProperties();
        properties.setEnabled(false);
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getBooleanFreshFailClosed(
                AppSettingService.EXTERNAL_REVIEW_CHECK_ENABLED,
                true
        )).thenReturn(true);
        ExternalReviewCheckRuntimeSwitch runtimeSwitch =
                new ExternalReviewCheckRuntimeSwitch(properties, settings);

        ExternalReviewCheckRuntimeSwitch.Status status = runtimeSwitch.setOperatorEnabled(true);

        verify(settings).setBoolean(AppSettingService.EXTERNAL_REVIEW_CHECK_ENABLED, true);
        assertThat(status.hardEnabled()).isFalse();
        assertThat(status.operatorEnabled()).isTrue();
        assertThat(status.enabled()).isFalse();
    }
}
