package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.t_telegrambot.MyTelegramBot;
import com.hunt.otziv.whatsapp.service.service.AdminNotifierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdminNotifierServiceImpl implements AdminNotifierService {

    private final MyTelegramBot telegramBotClient;

    private static final Long ADMIN_CHAT_ID = 794146111L; // заменить на реальный chatId

    @Override
    public void notifyAdmin(String message) {
        try {
            telegramBotClient.sendMessage(ADMIN_CHAT_ID, message, "Markdown");
            log.info("📩 Уведомление отправлено администратору: {}", message);
        } catch (Exception e) {
            log.error("❌ Ошибка при отправке уведомления админу: {}", e.getMessage(), e);
        }
    }
}
