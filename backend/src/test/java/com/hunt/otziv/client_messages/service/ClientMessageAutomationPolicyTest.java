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
    void stopReasonNamesTheSwitchAsTheSettingsPageShowsIt() {
        var settings=mock(AppSettingService.class);
        var now=LocalDateTime.of(2026,11,21,11,0);
        when(settings.getBooleanFreshFailClosed(anyString(),anyBoolean())).thenReturn(true);
        assertThat(ClientMessageAutomationPolicy.stopReason(settings,ClientMessageScenario.REVIEW_CHECK_REMINDER,now)).isNull();
        when(settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_ENABLED,true)).thenReturn(false);
        assertThat(ClientMessageAutomationPolicy.stopReason(settings,ClientMessageScenario.REVIEW_CHECK_REMINDER,now))
                .isEqualTo("выключен переключатель «Напоминать о проверке шаблонов»");
        // A delivery retry has no switch of its own: the reminder toggle does not stop it.
        assertThat(ClientMessageAutomationPolicy.stopReason(settings,ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY,now)).isNull();
        when(settings.getStringFresh(AppSettingService.CLIENT_MESSAGES_PAUSED_UNTIL,null)).thenReturn(now.plusHours(1).toString());
        assertThat(ClientMessageAutomationPolicy.stopReason(settings,ClientMessageScenario.PAYMENT_REMINDER,now))
                .startsWith("автоответчик на паузе");
        when(settings.getBooleanFreshFailClosed(AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED,true)).thenReturn(false);
        assertThat(ClientMessageAutomationPolicy.stopReason(settings,ClientMessageScenario.PAYMENT_REMINDER,now))
                .isEqualTo("выключен переключатель «Фоновая очередь задач работает»");
        assertThat(ScheduledClientMessageService.switchedOffMessage("выключен переключатель «Напоминать об оплате»"))
                .isEqualTo("Не отправлено: в настройках автоответчика (Справочники → Автоответчик) выключен переключатель «Напоминать об оплате».");
    }

    @org.junit.jupiter.api.Test
    void everyScenarioWithASwitchHasItsTitleAndAManualStep() {
        for (ClientMessageScenario scenario : ClientMessageScenario.values()) {
            boolean ownSwitch = scenario != ClientMessageScenario.PAYMENT_INVOICE_RETRY
                    && scenario != ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY;
            assertThat(com.hunt.otziv.client_messages.api.ClientMessageScenarioSwitches.switchTitle(scenario.name()) != null)
                    .as(scenario.name()).isEqualTo(ownSwitch);
            assertThat(com.hunt.otziv.client_messages.api.ClientMessageScenarioSwitches.manualAction(scenario.name()))
                    .as(scenario.name()).isNotBlank();
        }
    }

    @org.junit.jupiter.api.Test
    void theCardSaysWhoTurnsTheAutoresponderBackOn() {
        var switches = com.hunt.otziv.client_messages.api.ClientMessageScenarioSwitches.class;
        assertThat(com.hunt.otziv.client_messages.api.ClientMessageScenarioSwitches.whoTurnsItOn(
                "выключен переключатель «Напоминать об оплате»")).contains("общий переключатель для всех клиентов");
        assertThat(com.hunt.otziv.client_messages.api.ClientMessageScenarioSwitches.whoTurnsItOn(
                "автоответчик на паузе: защита от массовых ошибок остановила отправку, пауза снимется сама"))
                .startsWith("Пауза снимется сама");
        assertThat(switches).isNotNull();
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
