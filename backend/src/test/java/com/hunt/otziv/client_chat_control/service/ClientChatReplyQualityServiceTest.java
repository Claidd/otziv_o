package com.hunt.otziv.client_chat_control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hunt.otziv.client_chat_control.model.ClientChatReplyQuality;
import org.junit.jupiter.api.Test;

class ClientChatReplyQualityServiceTest {

    private final ClientChatReplyQualityService service = new ClientChatReplyQualityService();

    @Test
    void genericReplyToComplaintIsPartial() {
        var result = service.assess(
                "Почему отзывы не прошли? Исправьте, пожалуйста",
                "Хорошо"
        );

        assertEquals(ClientChatReplyQuality.PARTIAL, result.quality());
    }

    @Test
    void replyWithNextStepToProblemIsGood() {
        var result = service.assess(
                "Ссылка не открывается",
                "Сейчас проверим ссылку и отправим рабочую"
        );

        assertEquals(ClientChatReplyQuality.GOOD, result.quality());
    }
}

