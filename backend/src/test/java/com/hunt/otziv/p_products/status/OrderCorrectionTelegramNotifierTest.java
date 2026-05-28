package com.hunt.otziv.p_products.status;

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

        when(telegramService.sendMessage(700L, "Компания отправлен в Коррекцию - заметка комментарий\n https://o-ogo.ru/worker/correct"))
                .thenReturn(true);

        notifier.notifyWorkerCorrection(7L, 700L, "Компания", "заметка", "комментарий");

        verify(telegramService).sendMessage(
                700L,
                "Компания отправлен в Коррекцию - заметка комментарий\n https://o-ogo.ru/worker/correct"
        );
    }

    @Test
    void notifyWorkerCorrectionDoesNotThrowWhenTelegramReturnsFalse() {
        TelegramService telegramService = mock(TelegramService.class);
        OrderCorrectionTelegramNotifier notifier = new OrderCorrectionTelegramNotifier(telegramService);

        when(telegramService.sendMessage(700L, "Компания отправлен в Коррекцию -  \n https://o-ogo.ru/worker/correct"))
                .thenReturn(false);

        notifier.notifyWorkerCorrection(7L, 700L, "Компания", null, null);

        verify(telegramService).sendMessage(
                700L,
                "Компания отправлен в Коррекцию -  \n https://o-ogo.ru/worker/correct"
        );
    }

    @Test
    void notifyWorkerCorrectionSkipsMissingChatId() {
        TelegramService telegramService = mock(TelegramService.class);
        OrderCorrectionTelegramNotifier notifier = new OrderCorrectionTelegramNotifier(telegramService);

        notifier.notifyWorkerCorrection(7L, null, "Компания", "заметка", "комментарий");

        verifyNoInteractions(telegramService);
    }
}
