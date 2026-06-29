package com.hunt.otziv.maxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_messages.service.PublicationProgressPreferenceService;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
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

    @Mock
    private PublicationProgressPreferenceService publicationProgressPreferenceService;

    @Mock
    private ClientChatMessageTrackerService clientChatMessageTrackerService;

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

    @Test
    void handlesPublicationPreferenceCommandFromGroupMessage() throws Exception {
        MaxBotUpdateService service = service();
        JsonNode update = MAPPER.readTree("""
                {
                  "update_type": "message_created",
                  "message": {
                    "recipient": { "chat_id": -200 },
                    "sender": { "user_id": 303 },
                    "body": { "text": "включить уведомления" }
                  }
                }
                """);

        when(publicationProgressPreferenceService.handleMaxCommand(-200L, "включить уведомления"))
                .thenReturn(Optional.of(new PublicationProgressPreferenceService.PreferenceUpdate(
                        10L,
                        true,
                        "Оповещения о публикациях включены."
                )));
        when(publicationProgressPreferenceService.isPreferenceCommand("включить уведомления")).thenReturn(true);

        service.handleUpdate(update);

        verify(publicationProgressPreferenceService).handleMaxCommand(-200L, "включить уведомления");
        verify(maxBotClient).sendMessageToChat(-200L, "Оповещения о публикациях включены.");
        verifyNoMoreInteractions(maxGroupLinkService);
    }

    private MaxBotUpdateService service() {
        return new MaxBotUpdateService(
                maxGroupLinkService,
                maxBotClient,
                publicationProgressPreferenceService,
                clientChatMessageTrackerService
        );
    }
}
