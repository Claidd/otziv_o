package com.hunt.otziv.t_telegrambot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelegramNotificationServiceTest {

    @Mock private TelegramService telegramService;

    @Test
    void oneShotNotificationUsesExactlyOneProviderAttempt() {
        when(telegramService.sendMessageOnceWithInlineKeyboardMessageId(
                123L, "Премия", null, null
        )).thenReturn(Optional.of(55));

        assertThat(new TelegramNotificationService(telegramService).sendTextOnce(123L, "Премия"))
                .isTrue();
        verify(telegramService, only()).sendMessageOnceWithInlineKeyboardMessageId(
                123L, "Премия", null, null
        );
    }

    @Test
    void unconfirmedOneShotResultIsReportedAsFailure() {
        when(telegramService.sendMessageOnceWithInlineKeyboardMessageId(
                123L, "Премия", null, null
        )).thenReturn(Optional.empty());

        assertThat(new TelegramNotificationService(telegramService).sendTextOnce(123L, "Премия"))
                .isFalse();
        verify(telegramService, only()).sendMessageOnceWithInlineKeyboardMessageId(
                123L, "Премия", null, null
        );
    }
}
