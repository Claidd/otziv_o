package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;
import com.hunt.otziv.maxbot.service.MaxBotClient;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class ClientChatMessageSenderTest {

    @Mock private WhatsAppService whatsAppService;
    @Mock private TelegramService telegramService;
    @Mock private MaxBotClient maxBotClient;

    private ClientChatMessageSender sender;
    private com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations businessOperations;
    private ClientMessageOperationFence operationFence;
    private TelegramTransferCopyButton copyButton;
    private PublicationProgressPreferenceService progressPreferences;

    @BeforeEach
    void setUp() {
        businessOperations = mock(com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations.class);
        lenient().when(businessOperations.freezeForDispatch(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> new com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations.FrozenMessage(
                        call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)));
        operationFence = mock(ClientMessageOperationFence.class);
        lenient().when(operationFence.execute(anyString(), anyString(), nullable(String.class), anyString(), any()))
                .thenAnswer(call -> call.<java.util.function.Supplier<ClientMessageSendResult>>getArgument(4).get());
        progressPreferences = mock(PublicationProgressPreferenceService.class);
        sender = new ClientChatMessageSender(whatsAppService, telegramService, maxBotClient, businessOperations, operationFence, progressPreferences);
        copyButton = TelegramTransferCopyButton.fromFrozenTransferNumber("2202208238396676").orElseThrow();
    }

    @Test
    void publicationProgressFencesExactTextAndKeyboardAndRequiresPositiveReceipt() {
        Company company = company("https://t.me/example", 12345L, null);
        company.setId(7L);
        when(progressPreferences.appendTelegramOptOutHint("published")).thenReturn("published + hint");
        when(progressPreferences.disableCallbackData(7L)).thenReturn("disable:7");
        when(telegramService.sendMessageOnceWithInlineKeyboardMessageId(eq(12345L), eq("published + hint"), isNull(), anyList()))
                .thenReturn(java.util.Optional.of(91));
        ClientMessageSendResult result = sender.sendPublicationProgressWithOperationId(company, "c", "g", "published", true, "progress:7");
        assertTrue(result.sent()); assertEquals("91", result.messageId());
        verify(operationFence).execute(eq("progress:7"), eq("TELEGRAM"), eq("12345"),
                argThat(value -> value.contains("published + hint") && value.contains("disable:7")
                        && value.contains(PublicationProgressPreferenceService.DISABLE_BUTTON_TEXT)), any());
        verify(telegramService).sendMessageOnceWithInlineKeyboardMessageId(eq(12345L), eq("published + hint"), isNull(),
                argThat(rows -> rows.size() == 1 && rows.get(0).size() == 1
                        && "disable:7".equals(rows.get(0).get(0).getCallbackData())));
        verify(telegramService, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void publicationProgressUnknownBarrierPreventsResendAfterPlatformAndTemplateChange() {
        Company company = company("https://chat.whatsapp.com/replacement", null, null);
        var pending = new ClientMessageOperationFence.Snapshot("progress:7", "TELEGRAM", "UNKNOWN", "d", "e", "t", null);
        when(operationFence.lookup("progress:7")).thenReturn(java.util.Optional.of(pending));
        when(operationFence.execute(eq("progress:7"), eq("TELEGRAM"), isNull(), anyString(), any()))
                .thenReturn(ClientMessageSendResult.failed("operation_unknown", "pending reconciliation"));
        assertFalse(sender.sendPublicationProgressWithOperationId(company, "new", "new", "changed", false, "progress:7").sent());
        org.mockito.Mockito.verifyNoInteractions(telegramService, whatsAppService, maxBotClient);
    }

    @Test
    void publicationProgressWithoutControlsDoesNotInventAKeyboardOrAcceptMissingReceipt() {
        Company company = company("https://t.me/example", 12345L, null);
        when(telegramService.sendMessageOnceWithInlineKeyboardMessageId(eq(12345L), eq("published"), isNull(), eq(java.util.List.of())))
                .thenReturn(java.util.Optional.of(0));
        assertFalse(sender.sendPublicationProgressWithOperationId(company, "c", "g", "published", false, "progress:7").sent());
        org.mockito.Mockito.verifyNoInteractions(progressPreferences);
    }

    @Test
    void telegramPaymentUsesNativeCopyTextButton() {
        Company company = company("https://t.me/example", 12345L, null);
        when(telegramService.sendMessageWithCopyTextButton(
                12345L, "Счет и номер 2202208238396676",
                "Скопировать номер карты", "2202208238396676"
        )).thenReturn(true);

        ClientMessageSendResult result = sender.send(
                company, "manager", "whatsapp-group",
                "Счет и номер 2202208238396676", copyButton
        );

        assertTrue(result.sent());
        verify(telegramService).sendMessageWithCopyTextButton(
                12345L, "Счет и номер 2202208238396676",
                "Скопировать номер карты", "2202208238396676"
        );
        verify(telegramService, never()).sendMessage(12345L, "Счет и номер 2202208238396676");
        verify(telegramService, never()).sendMessage(12345L, "2202208238396676");
    }

    @Test
    void changedCompanyPlatformCannotRedirectAnExistingWhatsAppOccurrence() {
        Company company = company("https://t.me/replacement", 12345L, null);
        var frozen = new com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations.FrozenMessage(
                "stable-operation", "original-client", "send-group", "original-group", "original invoice");
        when(businessOperations.findFrozen("stable-operation")).thenReturn(java.util.Optional.of(frozen));
        when(whatsAppService.sendMessageToGroup("original-client", "original-group", "original invoice", "stable-operation"))
                .thenReturn(receipt("stable-operation"));
        assertTrue(sender.sendWithOperationId(company, "new-client", "new-group", "new text", copyButton, "stable-operation").sent());
        verify(whatsAppService).sendMessageToGroup("original-client", "original-group", "original invoice", "stable-operation");
        verifyNoMoreInteractions(whatsAppService);
        org.mockito.Mockito.verifyNoInteractions(telegramService, maxBotClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TELEGRAM", "MAX"})
    void persistedNonIdempotentAttemptCannotBeRedirectedToNewWhatsAppChat(String platform) {
        Company company = company("https://chat.whatsapp.com/new", 12345L, 98765L);
        var barrier = new ClientMessageOperationFence.Snapshot("stable-operation", platform, "UNKNOWN", "destination", "envelope", "token", null);
        when(operationFence.lookup("stable-operation")).thenReturn(java.util.Optional.of(barrier));
        when(operationFence.execute(eq("stable-operation"), eq(platform), anyString(), anyString(), any()))
                .thenReturn(ClientMessageSendResult.failed("operation_unknown", "reconcile"));
        assertFalse(sender.sendWithOperationId(company, "new-client", "new-group", "new invoice", copyButton, "stable-operation").sent());
        org.mockito.Mockito.verifyNoInteractions(whatsAppService, telegramService, maxBotClient, businessOperations);
    }

    @Test
    void persistentFenceIncludesPaymentCopyMetadataInEnvelopeBeforeDispatch() throws Exception {
        Company company = company("https://t.me/example", 12345L, null);
        when(telegramService.sendMessageOnceWithInlineKeyboardMessageId(eq(12345L), eq("invoice"), isNull(), any()))
                .thenReturn(java.util.Optional.of(77));
        assertTrue(sender.sendWithOperationId(company, "client", "group", "invoice", copyButton, "stable-operation").sent());
        var envelope = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(operationFence).execute(eq("stable-operation"), eq("TELEGRAM"), eq("12345"), envelope.capture(), any());
        var fields = new com.fasterxml.jackson.databind.ObjectMapper().readTree(envelope.getValue());
        assertEquals("invoice", fields.get(0).asText());
        assertEquals(copyButton.text(), fields.get(1).asText());
        assertEquals(copyButton.copyText(), fields.get(2).asText());
    }

    @Test
    void whatsappSendsCopyValueAsSeparateMessageWhenCopyMetadataExists() {
        Company company = company("https://chat.whatsapp.com/example", null, null);
        String message = "Счет";
        when(whatsAppService.sendMessageToGroup("manager", "whatsapp-group", message))
                .thenReturn("ok");
        when(whatsAppService.sendMessageToGroup("manager", "whatsapp-group", "2202208238396676"))
                .thenReturn("ok");

        ClientMessageSendResult result = sender.send(
                company, "manager", "whatsapp-group", message, copyButton
        );

        assertTrue(result.sent());
        InOrder delivery = inOrder(whatsAppService);
        delivery.verify(whatsAppService).sendMessageToGroup("manager", "whatsapp-group", message);
        delivery.verify(whatsAppService).sendMessageToGroup(
                "manager", "whatsapp-group", "2202208238396676"
        );
        verify(telegramService, never()).sendMessageWithCopyTextButton(
                12345L, message, "Скопировать номер карты", "2202208238396676"
        );
    }

    @Test
    void maxSendsCopyValueAsSeparateMessageWhenCopyMetadataExists() {
        Company company = company("https://max.ru/example", null, 98765L);
        String message = "Счет";
        when(maxBotClient.sendMessageToChat(98765L, message)).thenReturn(true);
        when(maxBotClient.sendMessageToChat(98765L, "2202208238396676")).thenReturn(true);

        ClientMessageSendResult result = sender.send(
                company, "manager", "whatsapp-group", message, copyButton
        );

        assertTrue(result.sent());
        InOrder delivery = inOrder(maxBotClient);
        delivery.verify(maxBotClient).sendMessageToChat(98765L, message);
        delivery.verify(maxBotClient).sendMessageToChat(98765L, "2202208238396676");
        verify(telegramService, never()).sendMessageWithCopyTextButton(
                98765L, message, "Скопировать номер карты", "2202208238396676"
        );
    }

    @Test
    void copyOnlyFailureDoesNotFailSuccessfulPrimaryMessage() {
        Company company = company("https://chat.whatsapp.com/example", null, null);
        when(whatsAppService.sendMessageToGroup("manager", "whatsapp-group", "Счет")).thenReturn("ok");
        when(whatsAppService.sendMessageToGroup("manager", "whatsapp-group", "2202208238396676"))
                .thenReturn("error");

        ClientMessageSendResult result = sender.send(
                company, "manager", "whatsapp-group", "Счет", copyButton
        );

        assertTrue(result.sent());
        verify(whatsAppService).sendMessageToGroup("manager", "whatsapp-group", "2202208238396676");
    }

    @Test
    void primaryFailureDoesNotSendCopyOnlyMessage() {
        Company company = company("https://chat.whatsapp.com/example", null, null);
        when(whatsAppService.sendMessageToGroup("manager", "whatsapp-group", "Счет")).thenReturn("error");

        ClientMessageSendResult result = sender.send(
                company, "manager", "whatsapp-group", "Счет", copyButton
        );

        assertFalse(result.sent());
        verify(whatsAppService, never()).sendMessageToGroup(
                "manager", "whatsapp-group", "2202208238396676"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "pending", "running", "unexpected_future_state"})
    void unconfirmedGatewayStatusCannotBecomeKnownUnsentEvenWithAMisleadingCode(String status) {
        Company company = company("https://chat.whatsapp.com/example", null, null);
        when(whatsAppService.sendMessageToGroup("manager", "group", "fixture", "stable-operation"))
                .thenReturn("{\"status\":\"" + status + "\",\"code\":\"gateway_not_ready\"}");

        ClientMessageSendResult result = sender.sendWithOperationId(
                company, "manager", "group", "fixture", null, "stable-operation");

        assertFalse(result.sent());
        assertFalse(ClientChatMessageSender.isKnownUnsent(result));
        assertEquals(status.equals("pending") ? "operation_pending" : "operation_unknown", result.errorCode());
    }

    @Test
    void retryUsesTheCommittedEnvelopeAfterTemplateAndDestinationChange() {
        var frozen = new com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations.FrozenMessage(
                "stable-operation", "original-manager", "send-group", "original-group", "original-message");
        when(businessOperations.freezeForDispatch(eq("stable-operation"), anyString(), eq("send-group"), anyString(), anyString()))
                .thenReturn(frozen);
        when(whatsAppService.sendMessageToGroup("original-manager", "original-group", "original-message", "stable-operation"))
                .thenReturn(receipt("stable-operation"));
        var company = company("https://chat.whatsapp.com/example", null, null);
        assertTrue(sender.sendWithOperationId(company, "new-manager", "new-group", "new-template", null, "stable-operation").sent());
        verify(whatsAppService).sendMessageToGroup("original-manager", "original-group", "original-message", "stable-operation");
        verifyNoMoreInteractions(whatsAppService);
    }

    @Test
    void failedDurableReservationNeverCallsProvider() {
        when(businessOperations.freezeForDispatch(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("storage unavailable"));
        var result = sender.sendWithOperationId(company("https://chat.whatsapp.com/example", null, null),
                "manager", "group", "message", null, "stable-operation");
        assertFalse(result.sent());
        verifyNoMoreInteractions(whatsAppService);
    }

    @Test
    void durableOperationKeyAlsoScopesTheOptionalCopyMessage() {
        Company company = company("https://chat.whatsapp.com/example", null, null);
        when(whatsAppService.sendMessageToGroup("manager", "group", "fixture", "stable-operation"))
                .thenReturn(receipt("stable-operation"));
        when(whatsAppService.sendMessageToGroup("manager", "group", copyButton.copyText(), "stable-operation:copy"))
                .thenReturn(receipt("stable-operation:copy"));

        assertTrue(sender.sendWithOperationId(company, "manager", "group", "fixture", copyButton,
                "stable-operation").sent());
        verify(whatsAppService).sendMessageToGroup("manager", "group", "fixture", "stable-operation");
        verify(whatsAppService).sendMessageToGroup("manager", "group", copyButton.copyText(), "stable-operation:copy");
    }

    @ParameterizedTest
    @ValueSource(strings = {"operation_unknown", "operation_pending", "operation_running",
            "operation_result_not_durable", "operation_payload_conflict", "telegram_exception",
            "max_exception", "unrecognized_future_failure"})
    void onlyProvenAdmissionFailuresPermitPaymentSourceRelease(String code) {
        assertFalse(ClientChatMessageSender.isKnownUnsent(ClientMessageSendResult.failed(code, "fixture")));
        assertTrue(ClientChatMessageSender.isKnownUnsent(ClientMessageSendResult.failed("gateway_not_ready", "fixture")));
        assertFalse(ClientChatMessageSender.isKnownUnsent(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ok", "{\"status\":\"ok\"}",
            "{\"status\":\"ok\",\"operationId\":\"other\",\"state\":\"SUCCEEDED\",\"messageId\":\"fixture\"}",
            "{\"status\":\"ok\",\"operationId\":\"stable-operation\",\"state\":\"RUNNING\",\"messageId\":\"fixture\"}",
            "{\"status\":\"ok\",\"operationId\":\"stable-operation\",\"state\":\"SUCCEEDED\"}",
            "{\"status\":\"ok\",\"operationId\":\"stable-operation\",\"state\":\"SUCCEEDED\",\"messageId\":true}",
            "{\"status\":\"ok\",\"operationId\":\"stable-operation\",\"state\":\"SUCCEEDED\",\"messageId\":0}"})
    void keyedReplyRequiresReceiptForTheSameCompletedOperation(String body) {
        Company company = company("https://chat.whatsapp.com/example", null, null);
        when(whatsAppService.sendMessageToGroup("manager", "group", "fixture", "stable-operation")).thenReturn(body);
        var result = sender.sendToPlatformWithOperationId(
                com.hunt.otziv.client_chat_control.model.ClientChatPlatform.WHATSAPP, company,
                "manager", "group", "group", "fixture", "stable-operation");
        assertEquals("operation_unknown", result.errorCode());
        assertFalse(result.sent());
    }

    @Test
    void keyedReplyAcceptsOnlyItsMatchingReceipt() {
        Company company = company("https://chat.whatsapp.com/example", null, null);
        when(whatsAppService.sendMessageToGroup("manager", "group", "fixture", "stable-operation"))
                .thenReturn(receipt("stable-operation"));
        var result = sender.sendToPlatformWithOperationId(
                com.hunt.otziv.client_chat_control.model.ClientChatPlatform.WHATSAPP, company,
                "manager", "group", "group", "fixture", "stable-operation");
        assertTrue(result.sent());
        assertEquals("fixture", result.messageId());
    }

    @Test
    void keyedTelegramReplyUsesOneAttemptAndRequiresPositiveMessageId() {
        Company company = company("https://t.me/example", 12345L, null);
        when(telegramService.sendMessageOnceWithInlineKeyboardMessageId(12345L, "fixture", null, java.util.List.of()))
                .thenReturn(java.util.Optional.of(81));

        var result = sender.sendToPlatformWithOperationId(
                com.hunt.otziv.client_chat_control.model.ClientChatPlatform.TELEGRAM,
                company, null, null, "12345", "fixture", "stable-operation");

        assertTrue(result.sent());
        assertEquals("81", result.messageId());
        verify(telegramService).sendMessageOnceWithInlineKeyboardMessageId(12345L, "fixture", null, java.util.List.of());
        verifyNoMoreInteractions(telegramService);
    }

    @Test
    void keyedTelegramUnconfirmedAttemptRemainsUnknownWithoutLegacyRetry() {
        Company company = company("https://t.me/example", 12345L, null);
        when(telegramService.sendMessageOnceWithInlineKeyboardMessageId(12345L, "fixture", null, java.util.List.of()))
                .thenReturn(java.util.Optional.empty());

        var result = sender.sendWithOperationId(company, null, null, "fixture", null, "stable-operation");

        assertFalse(result.sent());
        assertFalse(ClientChatMessageSender.isKnownUnsent(result));
        assertEquals("operation_unknown", result.errorCode());
        verify(telegramService).sendMessageOnceWithInlineKeyboardMessageId(12345L, "fixture", null, java.util.List.of());
        verifyNoMoreInteractions(telegramService);
    }

    @Test
    void keyedTelegramExceptionRemainsUnknownWithoutRetry() {
        Company company = company("https://t.me/example", 12345L, null);
        when(telegramService.sendMessageOnceWithInlineKeyboardMessageId(12345L, "fixture", null, java.util.List.of()))
                .thenThrow(new IllegalStateException("fixture timeout"));

        var result = sender.sendWithOperationId(company, null, null, "fixture", null, "stable-operation");

        assertFalse(result.sent());
        assertFalse(ClientChatMessageSender.isKnownUnsent(result));
        verify(telegramService).sendMessageOnceWithInlineKeyboardMessageId(12345L, "fixture", null, java.util.List.of());
        verifyNoMoreInteractions(telegramService);
    }

    @Test
    void keyedTelegramPreservesNativeCopyButtonInTheSingleRequest() {
        Company company = company("https://t.me/example", 12345L, null);
        when(telegramService.sendMessageOnceWithInlineKeyboardMessageId(eq(12345L), eq("fixture"), isNull(), anyList()))
                .thenAnswer(call -> {
                    var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(call.getArgument(3));
                    assertEquals("Скопировать номер карты", json.path(0).path(0).path("text").asText());
                    assertEquals(copyButton.copyText(), json.path(0).path(0).path("copy_text").path("text").asText());
                    return java.util.Optional.of(82);
                });

        assertTrue(sender.sendWithOperationId(company, null, null, "fixture", copyButton, "stable-operation").sent());

        verify(telegramService).sendMessageOnceWithInlineKeyboardMessageId(eq(12345L), eq("fixture"), isNull(), anyList());
        verifyNoMoreInteractions(telegramService);
    }

    @Test
    void keyedTelegramOversizeIsRejectedBeforeAnyPartialChunkCanBeSent() {
        var result = sender.sendWithOperationId(company("https://t.me/example", 12345L, null),
                null, null, "x".repeat(4097), null, "stable-operation");
        assertFalse(result.sent());
        assertTrue(ClientChatMessageSender.isKnownUnsent(result));
        assertEquals("payload_too_large", result.errorCode());
        verifyNoMoreInteractions(telegramService);
    }

    @Test
    void keyedMaxExplicitPlatformUsesOnlyReceiptReturningSingleAttempt() {
        Company company = company("https://max.ru/example", null, 98765L);
        when(maxBotClient.sendMessageToChatOnce(98765L, "fixture"))
                .thenReturn(new MaxBotClient.SendOnceResult("mid.fixture_81", null));
        var result = sender.sendToPlatformWithOperationId(
                com.hunt.otziv.client_chat_control.model.ClientChatPlatform.MAX,
                company, null, null, "98765", "fixture", "stable-operation");
        assertTrue(result.sent());
        assertEquals("mid.fixture_81", result.messageId());
        verify(maxBotClient).sendMessageToChatOnce(98765L, "fixture");
        verifyNoMoreInteractions(maxBotClient);
    }

    @Test
    void keyedMaxUnknownDoesNotRetryFallbackOrSendTheCopyMessage() {
        Company company = company("https://max.ru/example", null, 98765L);
        when(maxBotClient.sendMessageToChatOnce(98765L, "fixture"))
                .thenReturn(new MaxBotClient.SendOnceResult(null, "operation_unknown"));
        var result = sender.sendWithOperationId(company, null, null, "fixture", copyButton, "stable-operation");
        assertFalse(result.sent());
        assertFalse(ClientChatMessageSender.isKnownUnsent(result));
        assertEquals("operation_unknown", result.errorCode());
        verify(maxBotClient).sendMessageToChatOnce(98765L, "fixture");
        verifyNoMoreInteractions(maxBotClient, whatsAppService, telegramService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"payload_too_large", "invalid_request", "max_not_configured"})
    void keyedMaxLocalPreflightFailureIsKnownUnsent(String code) {
        Company company = company("https://max.ru/example", null, 98765L);
        when(maxBotClient.sendMessageToChatOnce(98765L, "fixture"))
                .thenReturn(new MaxBotClient.SendOnceResult(null, code));
        var result = sender.sendWithOperationId(company, null, null, "fixture", null, "stable-operation");
        assertFalse(result.sent());
        assertTrue(ClientChatMessageSender.isKnownUnsent(result));
        assertEquals(code, result.errorCode());
        verify(maxBotClient).sendMessageToChatOnce(98765L, "fixture");
        verifyNoMoreInteractions(maxBotClient);
    }

    @Test
    void keyedMaxCopyAlsoUsesSingleAttemptAfterConfirmedPrimary() {
        Company company = company("https://max.ru/example", null, 98765L);
        when(maxBotClient.sendMessageToChatOnce(98765L, "fixture"))
                .thenReturn(new MaxBotClient.SendOnceResult("mid.primary_81", null));
        when(maxBotClient.sendMessageToChatOnce(98765L, copyButton.copyText()))
                .thenReturn(new MaxBotClient.SendOnceResult(null, "operation_unknown"));
        var result = sender.sendWithOperationId(company, null, null, "fixture", copyButton, "stable-operation");
        assertTrue(result.sent());
        assertEquals("mid.primary_81", result.messageId());
        InOrder delivery = inOrder(maxBotClient);
        delivery.verify(maxBotClient).sendMessageToChatOnce(98765L, "fixture");
        delivery.verify(maxBotClient).sendMessageToChatOnce(98765L, copyButton.copyText());
        verifyNoMoreInteractions(maxBotClient);
    }

    private String receipt(String token) {
        return "{\"status\":\"ok\",\"operationId\":\"" + token + "\",\"state\":\"SUCCEEDED\",\"messageId\":\"fixture\"}";
    }

    private Company company(String urlChat, Long telegramChatId, Long maxChatId) {
        Company company = new Company();
        company.setId(1L);
        company.setTitle("Компания");
        company.setUrlChat(urlChat);
        company.setTelegramGroupChatId(telegramChatId);
        company.setMaxGroupChatId(maxChatId);
        return company;
    }
}
