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

    @Test
    void pdfDraftConfirmsThatDocumentWasReceived() {
        var suggestion = service.suggest("Документ-2026-07-26 180739.pdf");

        assertTrue(suggestion.message().contains("документ получили"));
        assertTrue(suggestion.message().contains("Проверим"));
    }

    @Test
    void paymentDraftConfirmsReceiptAndNextStep() {
        var suggestion = service.suggest("Я оплатила заказ");

        assertTrue(suggestion.message().contains("информацию об оплате получили"));
        assertTrue(suggestion.message().contains("Проверим поступление"));
    }
}
