package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.config.settings.service.AppSettingService;
import java.time.LocalDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class ClientMessageAutomationPolicyTest {
    @ParameterizedTest @EnumSource(ClientMessageScenario.class)
    void everyBackgroundScenarioHonorsStopPauseAndResume(ClientMessageScenario scenario) {
        var settings=mock(AppSettingService.class);
        var now=LocalDateTime.of(2026,11,3,11,0);
        when(settings.getBooleanFreshFailClosed(anyString(),anyBoolean())).thenReturn(true);
        assertThat(ClientMessageAutomationPolicy.allowed(settings,scenario,now)).isTrue();
        when(settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED,true)).thenReturn(false);
        assertThat(ClientMessageAutomationPolicy.allowed(settings,scenario,now)).isFalse();
        when(settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED,true)).thenReturn(true);
        when(settings.getStringFresh(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL,null)).thenReturn(now.plusMinutes(1).toString());
        assertThat(ClientMessageAutomationPolicy.allowed(settings,scenario,now)).isFalse();
        when(settings.getStringFresh(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL,null)).thenReturn(now.minusMinutes(1).toString());
        assertThat(ClientMessageAutomationPolicy.allowed(settings,scenario,now)).isTrue();
        when(settings.getStringFresh(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL,null)).thenThrow(new IllegalStateException("DB unavailable"));
        assertThat(ClientMessageAutomationPolicy.allowed(settings,scenario,now)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void masterAllowedHonorsOnlyTheMasterSwitchAndPause() {
        var settings=mock(AppSettingService.class);
        var now=LocalDateTime.of(2026,11,4,11,0);
        when(settings.getBooleanFreshFailClosed(anyString(),anyBoolean())).thenReturn(true);
        assertThat(ClientMessageAutomationPolicy.masterAllowed(settings,now)).isTrue();
        // A disabled scenario toggle does not stop master-only senders.
        when(settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED,true)).thenReturn(false);
        assertThat(ClientMessageAutomationPolicy.masterAllowed(settings,now)).isTrue();
        when(settings.getStringFresh(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL,null)).thenReturn(now.plusHours(1).toString());
        assertThat(ClientMessageAutomationPolicy.masterAllowed(settings,now)).isFalse();
        when(settings.getStringFresh(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL,null)).thenReturn(null);
        when(settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED,true)).thenReturn(false);
        assertThat(ClientMessageAutomationPolicy.masterAllowed(settings,now)).isFalse();
    }
}
