package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.t_telegrambot.api.TelegramNotifications;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Preserves the existing Telegram transport behavior without exporting the bot implementation. */
@Service
@RequiredArgsConstructor
public class TelegramNotificationService implements TelegramNotifications {
    private final TelegramService telegramService;

    @Override
    public boolean sendText(long chatId, String text) {
        return telegramService.sendMessage(chatId, text);
    }
}
