package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.maxbot.service.MaxBotClient;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A MAX file send that never reached the chat is a known non-delivery, so it is repeated, not reconciled. */
class ClientGroupDocumentAdapterTest {
    private final MaxBotClient max = mock(MaxBotClient.class);
    private final ClientGroupDocumentAdapter adapter =
            new ClientGroupDocumentAdapter(mock(WhatsAppService.class), mock(TelegramService.class), max);

    @Test
    void aFailedUploadSendsNothingAndMayBeRepeated() {
        when(max.uploadDocument(any(), anyString())).thenThrow(new IllegalStateException("MAX не подтвердил загрузку файла"));

        var result = adapter.send("whatsapp_lika", "MAX:-74473967837815", "Счёт", new byte[]{1}, "Счёт.pdf", "paper:1:invoice");

        assertThat(result.sent()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ClientGroupDocumentAdapter.MAX_UPLOAD_FAILED);
        assertThat(ClientMessageDelivery.isKnownUnsent(result)).isTrue();
        verify(max, never()).sendDocumentToChatOnce(anyLong(), anyString(), anyString());
    }

    @Test
    void aFileStillProcessingIsAKnownRefusalAndAnyOtherAnswerStaysUncertain() {
        when(max.uploadDocument(any(), anyString())).thenReturn("token");
        when(max.sendDocumentToChatOnce(-7L, "Счёт", "token"))
                .thenReturn(new MaxBotClient.SendOnceResult(null, MaxBotClient.ATTACHMENT_NOT_READY))
                .thenReturn(new MaxBotClient.SendOnceResult(null, "operation_unknown"))
                .thenReturn(new MaxBotClient.SendOnceResult("mid.file", null));

        var notReady = adapter.send("c", "MAX:-7", "Счёт", new byte[]{1}, "f.pdf", "op");
        var unknown = adapter.send("c", "MAX:-7", "Счёт", new byte[]{1}, "f.pdf", "op");
        var sent = adapter.send("c", "MAX:-7", "Счёт", new byte[]{1}, "f.pdf", "op");

        assertThat(ClientMessageDelivery.isKnownUnsent(notReady)).isTrue();
        assertThat(unknown.errorCode()).isEqualTo("operation_unknown");
        assertThat(ClientMessageDelivery.isKnownUnsent(unknown)).isFalse();
        assertThat(sent.sent()).isTrue();
        assertThat(sent.messageId()).isEqualTo("mid.file");
    }
}
