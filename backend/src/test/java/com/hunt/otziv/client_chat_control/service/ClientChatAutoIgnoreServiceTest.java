package com.hunt.otziv.client_chat_control.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import org.junit.jupiter.api.Test;

class ClientChatAutoIgnoreServiceTest {

    @Test
    void reciprocalAcknowledgementsDoNotCreateUnansweredActions() {
        AppSettingService settings = mock(AppSettingService.class);
        when(settings.getBoolean(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_AUTO_IGNORE_ENABLED,
                true
        )).thenReturn(true);
        when(settings.getInt(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_AUTO_IGNORE_MAX_LENGTH,
                60
        )).thenReturn(60);
        when(settings.getStringAllowEmpty(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_AUTO_IGNORE_PHRASES,
                ClientChatAutoIgnoreService.DEFAULT_PHRASES
        )).thenReturn(ClientChatAutoIgnoreService.DEFAULT_PHRASES);

        ClientChatAutoIgnoreService service = new ClientChatAutoIgnoreService(settings);

        for (String message : new String[]{
                "Вам спасибо",
                "И вам спасибо!",
                "Взаимно",
                "Хорошо спасибо большое"
        }) {
            assertTrue(service.shouldIgnore(message), message);
        }
    }
}
