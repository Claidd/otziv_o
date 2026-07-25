package com.hunt.otziv.client_chat_control.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientChatReplySuggestionServiceTest {

    private final ClientChatReplySuggestionService service =
            new ClientChatReplySuggestionService(new ClientChatResolutionPolicy());

    @Test
    void complaintDraftContainsNextStep() {
        var suggestion = service.suggest("Вы написали плохие отзывы и ещё требуете деньги");

        assertTrue(suggestion.message().contains("Проверим"));
        assertTrue(suggestion.message().contains("решением"));
    }
}
