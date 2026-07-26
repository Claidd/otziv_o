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

    @Test
    void shortThanksIsEnoughForAttachment() {
        var result = service.assess(
                "[Вложение: image]",
                "Спасибо"
        );

        assertEquals(ClientChatReplyQuality.NOT_APPLICABLE, result.quality());
        assertEquals("Для вложения достаточно подтверждения получения", result.reason());
    }

    @Test
    void shortThanksIsEnoughForPdfFilename() {
        var result = service.assess(
                "Документ-2026-07-26 180739.pdf",
                "Спасибо!"
        );

        assertEquals(ClientChatReplyQuality.NOT_APPLICABLE, result.quality());
    }

    @Test
    void explicitPdfAcceptanceIsGood() {
        var result = service.assess(
                "Документ-2026-07-26 180739.pdf",
                "Спасибо, документ получили и приняли. Проверим его."
        );

        assertEquals(ClientChatReplyQuality.GOOD, result.quality());
    }

    @Test
    void shortThanksIsEnoughForPaymentConfirmation() {
        var result = service.assess(
                "Оплату перевела, спасибо",
                "Спасибо!"
        );

        assertEquals(ClientChatReplyQuality.NOT_APPLICABLE, result.quality());
        assertEquals("Для подтверждения оплаты достаточно короткого ответа", result.reason());
    }

    @Test
    void explicitPaymentAcceptanceIsGood() {
        var result = service.assess(
                "Оплату перевела, спасибо",
                "Спасибо, информацию об оплате получили. Проверим поступление."
        );

        assertEquals(ClientChatReplyQuality.GOOD, result.quality());
    }

    @Test
    void paymentProblemStillRequiresMeaningfulReply() {
        var result = service.assess(
                "Оплата не прошла, появляется ошибка",
                "Спасибо"
        );

        assertEquals(ClientChatReplyQuality.PARTIAL, result.quality());
    }
}
