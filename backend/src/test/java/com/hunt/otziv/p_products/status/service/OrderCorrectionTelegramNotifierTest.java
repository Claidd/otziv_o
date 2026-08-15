package com.hunt.otziv.p_products.status.service;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderCorrectionTelegramNotifierTest {

    @Test
    void notifyWorkerCorrectionSendsExpectedMessage() {
        TelegramService telegramService = mock(TelegramService.class);
        OrderCorrectionTelegramNotifier notifier = new OrderCorrectionTelegramNotifier(telegramService);

        String message = "Компания отправлена в коррекцию."
                + "\nЗамечания клиента:\nОбщее замечание\nОтзыв #17: исправьте имя"
                + "\n\nhttps://o-ogo.ru/worker?section=correct";
        when(telegramService.sendMessage(700L, message))
                .thenReturn(true);

        notifier.notifyWorkerCorrection(7L, 700L, "Компания", "Общее замечание\nОтзыв #17: исправьте имя");

        verify(telegramService).sendMessage(700L, message);
    }

    @Test
    void notifyWorkerCorrectionDoesNotThrowWhenTelegramReturnsFalse() {
        TelegramService telegramService = mock(TelegramService.class);
        OrderCorrectionTelegramNotifier notifier = new OrderCorrectionTelegramNotifier(telegramService);

        String message = "Компания отправлена в коррекцию."
                + "\nЗамечания клиента не указаны."
                + "\n\nhttps://o-ogo.ru/worker?section=correct";
        when(telegramService.sendMessage(700L, message))
                .thenReturn(false);

        notifier.notifyWorkerCorrection(7L, 700L, "Компания", null);

        verify(telegramService).sendMessage(700L, message);
    }

    @Test
    void notifyWorkerCorrectionSkipsMissingChatId() {
        TelegramService telegramService = mock(TelegramService.class);
        OrderCorrectionTelegramNotifier notifier = new OrderCorrectionTelegramNotifier(telegramService);

        notifier.notifyWorkerCorrection(7L, null, "Компания", "замечание клиента");

        verifyNoInteractions(telegramService);
    }
}
