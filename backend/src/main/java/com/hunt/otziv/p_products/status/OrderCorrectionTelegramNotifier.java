package com.hunt.otziv.p_products.status;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderCorrectionTelegramNotifier {

    private static final String WORKER_CORRECTION_URL = "https://o-ogo.ru/worker/correct";

    private final TelegramService telegramService;

    @Async
    public void notifyWorkerCorrection(
            Long orderId,
            Long chatId,
            String companyTitle,
            String orderNote,
            String companyComments
    ) {
        if (chatId == null) {
            log.warn("Telegram-уведомление о коррекции заказа ID {} не отправлено: у специалиста нет chatId", orderId);
            return;
        }

        String message = normalize(companyTitle)
                + " отправлен в Коррекцию - "
                + normalize(orderNote)
                + " "
                + normalize(companyComments)
                + "\n "
                + WORKER_CORRECTION_URL;

        try {
            boolean sent = telegramService.sendMessage(chatId, message);
            if (sent) {
                log.info("Уведомление о коррекции заказа ID {} отправлено в Telegram chatId={}", orderId, chatId);
            } else {
                log.warn("Уведомление о коррекции заказа ID {} не отправлено в Telegram chatId={}. "
                        + "Ручной перевод заказа уже выполнен; проверьте сеть, TELEGRAM_BOT_TOKEN и доступность api.telegram.org",
                        orderId, chatId);
            }
        } catch (RuntimeException e) {
            log.warn("Уведомление о коррекции заказа ID {} не отправлено в Telegram chatId={}. "
                    + "Ручной перевод заказа уже выполнен; внешняя отправка не блокирует смену статуса",
                    orderId, chatId, e);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
