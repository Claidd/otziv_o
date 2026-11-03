package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.client_chat_control.api.*;
import com.hunt.otziv.client_chat_control.model.*;
import com.hunt.otziv.client_chat_control.repository.*;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReviewArchiveHistoryServiceTest {
    final ReminderContactHistory whatsapp=mock(ReminderContactHistory.class);
    final ClientChatMessageRepository messages=mock(ClientChatMessageRepository.class);
    final ClientChatUnansweredItemRepository unanswered=mock(ClientChatUnansweredItemRepository.class);
    final LocalDateTime since=LocalDateTime.now().minusDays(3);
    final ReviewArchiveHistory.Chat telegram=new ReviewArchiveHistory.Chat("TELEGRAM",null,"-100123");

    @Test void emptyTelegramDatabaseCannotProveSilenceOrFallBackToWhatsapp() {
        var service=new ReviewArchiveHistoryService(whatsapp,messages,unanswered,List.of());
        var check=service.inspect(telegram,since);
        assertThat(check.outcome()).isEqualTo(ReviewArchiveHistory.Outcome.HISTORY_MISSING);
        assertThat(check.reason()).contains("аккаунт Telegram");
        verifyNoInteractions(whatsapp);
    }
    @Test void savedTelegramReplyBlocksArchiveWithoutNeedingHistoricalAccess() {
        when(messages.existsByPlatformAndChatIdAndDirectionAndSenderRoleNotAndMessageAtGreaterThanEqual(
                ClientChatPlatform.TELEGRAM,"-100123",ClientChatDirection.INCOMING,ClientChatSenderRole.BOT,since)).thenReturn(true);
        var service=new ReviewArchiveHistoryService(whatsapp,messages,unanswered,List.of());
        assertThat(service.inspect(telegram,since).outcome()).isEqualTo(ReviewArchiveHistory.Outcome.REPLY_FOUND);
        verifyNoInteractions(whatsapp);
    }
    @Test void unresolvedOlderReplyBlocksEvenWhenTheNewPeriodIsEmpty() {
        when(unanswered.findByPlatformAndChatIdAndStatus(ClientChatPlatform.TELEGRAM,"-100123",ClientChatUnansweredStatus.OPEN))
                .thenReturn(List.of(new ClientChatUnansweredItem()));
        var service=new ReviewArchiveHistoryService(whatsapp,messages,unanswered,List.of());
        assertThat(service.inspect(telegram,since).outcome()).isEqualTo(ReviewArchiveHistory.Outcome.REPLY_FOUND);
        verifyNoInteractions(messages);
    }
    @Test void configuredReaderReceivesTheExactChannelAndFailureNeverBecomesClear() {
        var reader=mock(ReviewArchiveHistory.Reader.class);
        when(reader.platform()).thenReturn("MAX");when(reader.read(any(),any())).thenThrow(new IllegalStateException("timeout"));
        var service=new ReviewArchiveHistoryService(whatsapp,messages,unanswered,List.of(reader));
        var chat=new ReviewArchiveHistory.Chat("MAX",null,"-912");
        assertThat(service.inspect(chat,since).outcome()).isEqualTo(ReviewArchiveHistory.Outcome.HISTORY_UNAVAILABLE);
        verify(reader).read(chat,since);verifyNoInteractions(whatsapp);
    }
    @Test void whatsappStillRequiresFreshProviderHistory() {
        when(whatsapp.hasReplyBlockingArchive("client","group",since)).thenThrow(new IllegalStateException("offline"));
        var service=new ReviewArchiveHistoryService(whatsapp,messages,unanswered,List.of());
        assertThat(service.inspect(new ReviewArchiveHistory.Chat("WHATSAPP","client","group"),since).outcome())
                .isEqualTo(ReviewArchiveHistory.Outcome.HISTORY_UNAVAILABLE);
    }
}
