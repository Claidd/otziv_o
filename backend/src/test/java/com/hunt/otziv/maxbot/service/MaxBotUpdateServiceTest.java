package com.hunt.otziv.maxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaxBotUpdateServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private MaxGroupLinkService maxGroupLinkService;

    @Mock
    private MaxBotClient maxBotClient;

    @Test
    void handlesBotStartedDeepLinkPayload() throws Exception {
        MaxBotUpdateService service = service();
        JsonNode update = MAPPER.readTree("""
                {
                  "update_type": "bot_started",
                  "chat_id": 101,
                  "user": { "user_id": 202 },
                  "payload": "c1160_Fvq8FSz2augL"
                }
                """);

        when(maxGroupLinkService.handleBotStarted(202L, "c1160_Fvq8FSz2augL"))
                .thenReturn(Optional.of("started"));

        service.handleUpdate(update);

        verify(maxGroupLinkService).handleBotStarted(202L, "c1160_Fvq8FSz2augL");
        verify(maxBotClient).sendMessageToChat(101L, "started");
    }

    @Test
    void handlesManualStartCommandFromMessageCreated() throws Exception {
        MaxBotUpdateService service = service();
        JsonNode update = MAPPER.readTree("""
                {
                  "update_type": "message_created",
                  "message": {
                    "sender": { "user_id": 303 },
                    "body": { "text": "/start c1160_Fvq8FSz2augL" }
                  }
                }
                """);

        when(maxGroupLinkService.handleBotStarted(303L, "c1160_Fvq8FSz2augL"))
                .thenReturn(Optional.of("started"));

        service.handleUpdate(update);

        verify(maxGroupLinkService).handleBotStarted(303L, "c1160_Fvq8FSz2augL");
        verify(maxBotClient).sendMessageToUser(303L, "started");
    }

    @Test
    void ignoresMessageCreatedWithoutStartPayload() throws Exception {
        MaxBotUpdateService service = service();
        JsonNode update = MAPPER.readTree("""
                {
                  "update_type": "message_created",
                  "message": {
                    "sender": { "user_id": 303 },
                    "body": { "text": "hello" }
                  }
                }
                """);

        service.handleUpdate(update);

        verifyNoMoreInteractions(maxGroupLinkService, maxBotClient);
    }

    private MaxBotUpdateService service() {
        return new MaxBotUpdateService(maxGroupLinkService, maxBotClient);
    }
}
