package com.hunt.otziv.maxbot.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaxBotWebhookRegistrationServiceTest {

    @Test
    void registersWebhookFromAppBaseUrlOnStartup() {
        MaxBotClient client = configuredClient();
        MaxBotWebhookRegistrationService service = new MaxBotWebhookRegistrationService(
                client,
                true,
                "https://o-ogo.ru/",
                "",
                "secret_123",
                "bot_started, bot_added, message_created"
        );

        service.registerWebhookOnStartup();

        verify(client).registerWebhook(
                "https://o-ogo.ru/webhook/max",
                List.of("bot_started", "bot_added", "message_created"),
                "secret_123"
        );
    }

    @Test
    void explicitWebhookUrlOverridesAppBaseUrl() {
        MaxBotClient client = configuredClient();
        MaxBotWebhookRegistrationService service = new MaxBotWebhookRegistrationService(
                client,
                true,
                "https://o-ogo.ru",
                "https://api.example.ru/webhook/max",
                "secret-123",
                "bot_started,bot_added"
        );

        service.registerWebhookOnStartup();

        verify(client).registerWebhook(
                "https://api.example.ru/webhook/max",
                List.of("bot_started", "bot_added"),
                "secret-123"
        );
    }

    @Test
    void skipsNonHttpsUrl() {
        MaxBotClient client = configuredClient();
        MaxBotWebhookRegistrationService service = new MaxBotWebhookRegistrationService(
                client,
                true,
                "http://localhost:8088",
                "",
                "secret_123",
                "bot_started,bot_added,message_created"
        );

        service.registerWebhookOnStartup();

        verify(client, never()).registerWebhook(anyString(), anyList(), anyString());
    }

    @Test
    void skipsInvalidSecret() {
        MaxBotClient client = configuredClient();
        MaxBotWebhookRegistrationService service = new MaxBotWebhookRegistrationService(
                client,
                true,
                "https://o-ogo.ru",
                "",
                "bad secret",
                "bot_started,bot_added,message_created"
        );

        service.registerWebhookOnStartup();

        verify(client, never()).registerWebhook(anyString(), anyList(), anyString());
    }

    @Test
    void parsesUpdateTypesWithoutDuplicates() {
        MaxBotWebhookRegistrationService service = new MaxBotWebhookRegistrationService(
                mock(MaxBotClient.class),
                true,
                "https://o-ogo.ru",
                "",
                "secret_123",
                "bot_started, bot_added, bot_started, message_created"
        );

        assertEquals(List.of("bot_started", "bot_added", "message_created"), service.parsedUpdateTypes());
    }

    private MaxBotClient configuredClient() {
        MaxBotClient client = mock(MaxBotClient.class);
        when(client.isConfigured()).thenReturn(true);
        return client;
    }
}
