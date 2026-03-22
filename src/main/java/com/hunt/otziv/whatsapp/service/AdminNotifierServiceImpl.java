package com.hunt.otziv.whatsapp.service;

//import com.hunt.otziv.t_telegrambot.MyTelegramBot;
import com.hunt.otziv.whatsapp.service.service.AdminNotifierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdminNotifierServiceImpl implements AdminNotifierService {

//    private final MyTelegramBot telegramBotClient;

    // Список chatId админов
    private static final List<Long> ADMIN_CHAT_IDS = List.of(
            794146111L,       // админ №1
            828987226L        // админ №2
    );

    @Override
    public void notifyAdmin(String message) {
        for (Long chatId : ADMIN_CHAT_IDS) {
            try {
//                telegramBotClient.sendMessage(chatId, message, "Markdown");
//                log.info("📩 Уведомление отправлено админу {}: {}", chatId, message);
            } catch (Exception e) {
                log.error("❌ Ошибка при отправке админу {}: {}", chatId, e.getMessage(), e);
            }
        }
    }
}

