package com.hunt.otziv.performers.service;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PerformerTelegramCallbackServiceTest {

    @Mock private PerformerAssignmentService assignmentService;

    private PerformerTelegramCallbackService service;

    @BeforeEach
    void setUp() {
        service = new PerformerTelegramCallbackService(assignmentService);
    }

    @Test
    void acceptForwardsBothTelegramSenderAndChatIdentity() {
        Optional<String> result = service.handle(callback("perf:accept:42", 700L, 700L));

        assertEquals(Optional.of("Задание принято"), result);
        verify(assignmentService).acceptOfferFromTelegram(42L, 700L, 700L);
    }

    @Test
    void declineForwardsBothTelegramSenderAndChatIdentity() {
        Optional<String> result = service.handle(callback("perf:decline:43", 700L, 700L));

        assertEquals(Optional.of("Отказ зафиксирован"), result);
        verify(assignmentService).declineOfferFromTelegram(43L, 700L, 700L);
    }

    private CallbackQuery callback(String data, long telegramUserId, long telegramChatId) {
        Chat chat = new Chat();
        chat.setId(telegramChatId);
        chat.setType("private");
        Message message = new Message();
        message.setChat(chat);
        org.telegram.telegrambots.meta.api.objects.User from =
                new org.telegram.telegrambots.meta.api.objects.User();
        from.setId(telegramUserId);
        CallbackQuery callback = new CallbackQuery();
        callback.setData(data);
        callback.setFrom(from);
        callback.setMessage(message);
        return callback;
    }
}
