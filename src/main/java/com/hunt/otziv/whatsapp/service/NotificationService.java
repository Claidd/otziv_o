package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.t_telegrambot.MyTelegramBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {

    private final MyTelegramBot myTelegramBot;

    @Async
    public void sendAdminAlert(String msg, List<Long> adminChatIds) {
        for (Long chatId : adminChatIds) {
            try {
                myTelegramBot.sendMessage(chatId, msg, "Markdown");
            } catch (Exception e) {
                log.error("❌ Ошибка при отправке Telegram-сообщения админу {}: {}", chatId, e.getMessage());
            }
        }
    }
}
