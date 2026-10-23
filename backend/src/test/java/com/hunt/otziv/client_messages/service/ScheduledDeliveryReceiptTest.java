package com.hunt.otziv.client_messages.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.maxbot.service.MaxBotClient;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations;
import com.hunt.otziv.whatsapp.dto.WhatsAppOperationEnvelope;
import com.hunt.otziv.whatsapp.dto.WhatsAppOperationStatus;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScheduledDeliveryReceiptTest {
    private final WhatsAppService whatsapp = mock(WhatsAppService.class);
    private final TelegramService telegram = mock(TelegramService.class);
    private final MaxBotClient max = mock(MaxBotClient.class);
    private final WhatsAppBusinessOperations operations = mock(WhatsAppBusinessOperations.class);
    private final ClientMessageOperationFence fences = mock(ClientMessageOperationFence.class);
    private final ClientChatMessageSender sender = new ClientChatMessageSender(whatsapp, telegram, max, operations, fences,
            mock(PublicationProgressPreferenceService.class));

    private void frozen() {
        when(operations.findFrozen("operation:1")).thenReturn(Optional.of(new WhatsAppBusinessOperations.FrozenMessage(
                "operation:1", "client", "send-group", "12345678@g.us", "1200 руб.")));
    }

    @Test void exactSuccessfulWhatsAppReceiptCanFinalizeWithoutSending() {
        frozen();
        when(whatsapp.getOperationStatus("client", "operation:1")).thenReturn(new WhatsAppOperationStatus(
                "operation:1", "SUCCEEDED", "provider-42", WhatsAppOperationEnvelope.groupHash("client", "12345678@g.us", "1200 руб.")));
        assertThat(sender.recordedOutcome("operation:1")).isEqualTo(ClientMessageSendResult.sent("WhatsApp", "provider-42"));
        verify(whatsapp).getOperationStatus("client", "operation:1");
        verifyNoMoreInteractions(whatsapp);
        verifyNoInteractions(telegram, max);
    }

    @ParameterizedTest @ValueSource(strings = {"NOT_FOUND", "PREPARED", "UNKNOWN", "FAILED_KNOWN"})
    void absentOrIncompleteGatewayEvidenceNeverAuthorizesReplay(String state) {
        frozen();
        when(whatsapp.getOperationStatus("client", "operation:1")).thenReturn(new WhatsAppOperationStatus("operation:1", state, null, null));
        assertThat(sender.recordedOutcome("operation:1").errorCode()).isEqualTo("operation_unknown");
        verify(whatsapp).getOperationStatus("client", "operation:1");
        verifyNoMoreInteractions(whatsapp);
        verifyNoInteractions(telegram, max);
    }

    @Test void receiptForDifferentOperationOrPayloadIsInsufficient() {
        frozen();
        when(whatsapp.getOperationStatus("client", "operation:1"))
                .thenReturn(new WhatsAppOperationStatus("operation:2", "SUCCEEDED", "42", WhatsAppOperationEnvelope.groupHash("client", "12345678@g.us", "1200 руб.")),
                        new WhatsAppOperationStatus("operation:1", "SUCCEEDED", "42", WhatsAppOperationEnvelope.groupHash("client", "12345678@g.us", "9999 руб.")));
        assertThat(sender.recordedOutcome("operation:1").sent()).isFalse();
        assertThat(sender.recordedOutcome("operation:1").sent()).isFalse();
    }

    @Test void durableTelegramReceiptUsesLocalFenceAndDoesNotContactProviders() {
        var result = ClientMessageSendResult.sent("Telegram", "42");
        when(fences.lookup("operation:1")).thenReturn(Optional.of(new ClientMessageOperationFence.Snapshot(
                "operation:1", "TELEGRAM", "SUCCEEDED", "destination", "envelope", "token", result)));
        assertThat(sender.recordedOutcome("operation:1")).isEqualTo(result);
        verifyNoInteractions(whatsapp, telegram, max, operations);
    }

    @Test void missingLocalAndRemoteEnvelopeDoesNotSend() {
        assertThat(sender.recordedOutcome("operation:1").errorCode()).isEqualTo("operation_unknown");
        verifyNoInteractions(whatsapp, telegram, max);
    }

    @Test void unknownOperationCanBeConfirmedOnlyByGatewayValidatedChatReceipt() {
        frozen();
        when(whatsapp.recoverOperationFromHistory("client", "operation:1", "send-group", "12345678@g.us", "1200 руб."))
                .thenReturn(ClientMessageSendResult.sent("WhatsApp", "provider-42"));
        assertThat(sender.recoverRecordedOutcomeFromChat("operation:1", null))
                .isEqualTo(ClientMessageSendResult.sent("WhatsApp", "provider-42"));
        verify(whatsapp).recoverOperationFromHistory("client", "operation:1", "send-group", "12345678@g.us", "1200 руб.");
        verify(whatsapp, never()).reconcileGroupMessagesForReceipt(any(), any());
        verify(whatsapp, never()).sendMessageToGroup(any(), any(), any(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"whatsapp_history_checked_no_receipt", "whatsapp_history_incomplete",
            "whatsapp_history_evidence_ambiguous", "whatsapp_history_evidence_unresolved", "whatsapp_history_unavailable"})
    void unresolvedHistoryNeverAuthorizesReplay(String code) {
        frozen();
        when(whatsapp.recoverOperationFromHistory(any(), any(), any(), any(), any()))
                .thenReturn(ClientMessageSendResult.failed(code, "waiting"));
        var result = sender.recoverRecordedOutcomeFromChat("operation:1", LocalDateTime.of(2026, 9, 8, 10, 0));
        assertThat(result.errorCode()).isEqualTo(code);
        assertThat(ClientChatMessageSender.isKnownUnsent(result)).isFalse();
        verify(whatsapp, never()).sendMessageToGroup(any(), any(), any(), any());
        verify(whatsapp, never()).sendMessage(any(), any(), any(), any());
    }

    @Test void directChatUsesItsFrozenPhoneAndNeverCurrentCompanyRoute() {
        when(operations.findFrozen("operation:1")).thenReturn(Optional.of(new WhatsAppBusinessOperations.FrozenMessage(
                "operation:1", "client", "send", "79991234567", "1200 руб.")));
        when(whatsapp.recoverOperationFromHistory("client", "operation:1", "send", "79991234567", "1200 руб."))
                .thenReturn(ClientMessageSendResult.sent("WhatsApp", "direct-42"));
        assertThat(sender.recoverRecordedOutcomeFromChat("operation:1", null).messageId()).isEqualTo("direct-42");
        verify(whatsapp, never()).sendMessage(any(), any(), any(), any());
    }

    @Test void unavailableHistoryNeverBecomesKnownUnsent() {
        frozen();
        when(whatsapp.recoverOperationFromHistory(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("disconnected"));
        var result = sender.recoverRecordedOutcomeFromChat("operation:1", null);
        assertThat(result.errorCode()).isEqualTo("whatsapp_history_unavailable");
        assertThat(ClientChatMessageSender.isKnownUnsent(result)).isFalse();
    }

    @Test void telegramUnknownFenceCannotBeReinterpretedUsingWhatsAppHistory() {
        when(fences.lookup("operation:1")).thenReturn(Optional.of(new ClientMessageOperationFence.Snapshot(
                "operation:1", "TELEGRAM", "UNKNOWN", "destination", "envelope", "token", null)));
        assertThat(sender.recoverRecordedOutcomeFromChat("operation:1", null).sent()).isFalse();
        verifyNoInteractions(whatsapp, telegram, max, operations);
    }
}
