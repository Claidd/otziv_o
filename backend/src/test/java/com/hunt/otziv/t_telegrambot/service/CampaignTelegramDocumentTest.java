package com.hunt.otziv.t_telegrambot.service;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignTelegramDocumentTest {
    @Test
    void uploadsUtf8TextAsDocumentAndRequiresConfirmedMessageId() throws Exception {
        var telegram = new CapturingTelegram(true);
        byte[] content = "Предложение новой услуги".getBytes(StandardCharsets.UTF_8);
        assertThat(telegram.sendDocumentOnceMessageId(12L, content, "offer.txt", "Новое предложение")).contains(91);
        assertThat(telegram.document.getChatId()).isEqualTo("12");
        assertThat(telegram.document.getDocument().isNew()).isTrue();
        assertThat(telegram.document.getDocument().getMediaName()).isEqualTo("offer.txt", "Новое предложение");
        assertThat(telegram.document.getDocument().getNewMediaStream().readAllBytes()).isEqualTo(content);
        assertThat(telegram.document.getProtectContent()).isNotEqualTo(Boolean.TRUE);
        assertThat(telegram.attempts).isEqualTo(1);
    }

    @Test
    void disabledSendingNeverExecutesTelegramRequest() {
        var telegram = new CapturingTelegram(false);
        assertThat(telegram.sendDocumentOnceMessageId(12L, new byte[]{1}, "offer.txt", "Новое предложение")).isEmpty();
        assertThat(telegram.attempts).isZero();
    }

    @Test
    void offerCaptionIsPreserved() throws Exception {
        var telegram = new CapturingTelegram(true);
        assertThat(telegram.sendDocumentOnceMessageId(12L,new byte[]{1},"offer.pdf","Новое предложение")).contains(91);
        assertThat(telegram.document.getCaption()).isEqualTo("Новое предложение");
        assertThat(telegram.attempts).isEqualTo(1);
    }

    @Test
    void ambiguousFailureIsNotRetriedInsideTransportOrReportedAsSuccess() {
        var telegram = new CapturingTelegram(true);
        telegram.fail = true;
        assertThat(telegram.sendDocumentOnceMessageId(12L, new byte[]{1}, "offer.txt", "Новое предложение")).isEmpty();
        assertThat(telegram.attempts).isEqualTo(1);
    }

    @Test
    void malformedSuccessIsNotConfirmed() {
        var telegram = new CapturingTelegram(true);
        telegram.messageId = null;
        assertThat(telegram.sendDocumentOnceMessageId(12L, new byte[]{1}, "offer.txt", "Новое предложение")).isEmpty();
        telegram.messageId = 0;
        assertThat(telegram.sendDocumentOnceMessageId(12L, new byte[]{1}, "offer.txt", "Новое предложение")).isEmpty();
    }

    private static class CapturingTelegram extends TelegramService {
        SendDocument document;
        Integer messageId = 91;
        boolean fail;
        int attempts;

        CapturingTelegram(boolean enabled) {
            super(new DefaultBotOptions(), "123456:abcdefghijklmnopqrstuvwxyz", "test_bot", enabled,
                    "", null, null, null, null, null);
        }

        @Override
        Message executeTelegramDocument(SendDocument document) throws TelegramApiException {
            attempts++;
            if (fail) throw new TelegramApiException("Synthetic transport timeout");
            this.document = document;
            Message message = new Message();
            message.setMessageId(messageId);
            return message;
        }
    }
}
