package com.hunt.otziv.t_telegrambot.service;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.groupadministration.*;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.chatmember.*;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TelegramChatAccessServiceTest {
    TelegramService bot=mock(TelegramService.class);
    TelegramChatAccessService access=new TelegramChatAccessService(bot);
    @Test void disabledStandDoesNotMakeNetworkRequests() throws Exception {
        assertThat(access.check(-1001).status()).isEqualTo("UNKNOWN");
        verify(bot,never()).executeGetChat(any()); verify(bot,never()).executeGetChatMember(any());
    }
    @Test void definitiveAbsenceIsDifferentFromTransportFailure() throws Exception {
        when(bot.canSendDocuments()).thenReturn(true);
        for(int code:new int[]{400,403,429,500}) {
            var failure=mock(TelegramApiRequestException.class);
            when(failure.getErrorCode()).thenReturn(code);when(failure.getApiResponse()).thenReturn("chat not found");
            doThrow(failure).when(bot).executeGetChat(any(GetChat.class));
            assertThat(access.check(-1001).status()).isEqualTo(code==400||code==403?"UNAVAILABLE":"UNKNOWN");
        }
    }
    @Test void membershipAndRestrictionsAreCheckedWithoutSending() throws Exception {
        when(bot.canSendDocuments()).thenReturn(true);when(bot.getBotToken()).thenReturn("123456:fixture");
        var chat=new Chat();chat.setId(-1001L);chat.setType("supergroup");
        when(bot.executeGetChat(any(GetChat.class))).thenReturn(chat);
        var member=mock(ChatMember.class);when(member.getStatus()).thenReturn("member");
        when(bot.executeGetChatMember(any(GetChatMember.class))).thenReturn(member);
        assertThat(access.check(-1001).status()).isEqualTo("AVAILABLE");
        when(member.getStatus()).thenReturn("left");assertThat(access.check(-1001).status()).isEqualTo("UNAVAILABLE");
        var restricted=mock(ChatMemberRestricted.class);when(restricted.getStatus()).thenReturn("restricted");
        when(restricted.getIsMember()).thenReturn(true);
        when(restricted.getCanSendMessages()).thenReturn(false);when(bot.executeGetChatMember(any())).thenReturn(restricted);
        assertThat(access.check(-1001).reason()).isEqualTo("chat_permission_denied");
        verify(bot,never()).sendMessageOnceWithInlineKeyboardMessageId(anyLong(),anyString(),any(),any());
    }
}
