package com.hunt.otziv.t_telegrambot.service;

import org.junit.jupiter.api.Test;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import java.util.List;
import static org.mockito.Mockito.*;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramDocumentDeliveryTest {
    @Test
    void uploadsUtf8TextAsDocumentAndRequiresConfirmedMessageId() throws Exception {
        var telegram = new CapturingTelegram(true);
        byte[] content = "Запрос поставщику: дубли аккаунтов".getBytes(StandardCharsets.UTF_8);
        assertThat(telegram.sendDocumentOnceMessageId(12L, content, "duplicates.txt")).contains(91);
        assertThat(telegram.document.getChatId()).isEqualTo("12");
        assertThat(telegram.document.getDocument().isNew()).isTrue();
        assertThat(telegram.document.getDocument().getMediaName()).isEqualTo("duplicates.txt");
        assertThat(telegram.document.getDocument().getNewMediaStream().readAllBytes()).isEqualTo(content);
        assertThat(telegram.document.getProtectContent()).isNotEqualTo(Boolean.TRUE);
        assertThat(telegram.attempts).isEqualTo(1);
    }

    @Test
    void disabledSendingNeverExecutesTelegramRequest() {
        var telegram = new CapturingTelegram(false);
        assertThat(telegram.sendDocumentOnceMessageId(12L, new byte[]{1}, "duplicates.txt")).isEmpty();
        assertThat(telegram.attempts).isZero();
    }

    @Test
    void offerCaptionIsPreservedWithoutDuplicateReportText() throws Exception {
        var telegram = new CapturingTelegram(true);
        assertThat(telegram.sendDocumentOnceMessageId(12L,new byte[]{1},"offer.pdf","Новое предложение")).contains(91);
        assertThat(telegram.document.getCaption()).isEqualTo("Новое предложение");
        assertThat(telegram.attempts).isEqualTo(1);
    }

    @Test
    void ambiguousFailureIsNotRetriedInsideTransportOrReportedAsSuccess() {
        var telegram = new CapturingTelegram(true);
        telegram.fail = true;
        assertThat(telegram.sendDocumentOnceMessageId(12L, new byte[]{1}, "duplicates.txt")).isEmpty();
        assertThat(telegram.attempts).isEqualTo(1);
    }

    @Test
    void malformedSuccessDoesNotAllowDeletingReport() {
        var telegram = new CapturingTelegram(true);
        telegram.messageId = null;
        assertThat(telegram.sendDocumentOnceMessageId(12L, new byte[]{1}, "duplicates.txt")).isEmpty();
        telegram.messageId = 0;
        assertThat(telegram.sendDocumentOnceMessageId(12L, new byte[]{1}, "duplicates.txt")).isEmpty();
    }

    @Test
    void usesConfiguredChatsWithoutLoadingUsers() {
        var users = mock(UserService.class);
        var telegram = new CapturingTelegram("11,11,0,22", users);
        assertThat(telegram.adminDocumentRecipients()).containsExactly(11L, 22L);
        verifyNoInteractions(users);
    }

    @Test
    void fallsBackOnlyToActiveAdminsAndDeduplicatesChats() {
        var users = mock(UserService.class);
        User active = new User(); active.setActive(true); active.setTelegramChatId(33L);
        User inactive = new User(); inactive.setActive(false); inactive.setTelegramChatId(44L);
        when(users.getAllOwners("ROLE_ADMIN")).thenReturn(List.of(active, inactive, active));
        var telegram = new CapturingTelegram("", users);
        assertThat(telegram.adminDocumentRecipients()).containsExactly(33L);
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

        CapturingTelegram(String admins, UserService users) {
            super(new DefaultBotOptions(), "123456:abcdefghijklmnopqrstuvwxyz", "test_bot", true,
                    admins, null, users, null, null, null);
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
