package com.hunt.otziv.client_chat_control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientChatResolutionPolicyTest {

    private final ClientChatResolutionPolicy policy = new ClientChatResolutionPolicy();

    @Test
    void explicitQuestionCannotBeClosedWithoutResponse() {
        var assessment = policy.assess("Просьба публиковать один отзыв в 3-5 дней?");

        assertTrue(assessment.responseRequired());
        assertFalse(assessment.safeNoResponse());
    }

    @Test
    void complaintRequiresResponseAndAction() {
        var assessment = policy.assess("Вы написали плохие отзывы и ещё требуете деньги");

        assertTrue(assessment.responseRequired());
        assertTrue(assessment.actionRequired());
    }

    @Test
    void acknowledgementCanBeClosedWithoutResponse() {
        var assessment = policy.assess("Спасибо большое");

        assertFalse(assessment.responseRequired());
        assertTrue(assessment.safeNoResponse());
    }

    @Test
    void reciprocalAcknowledgementsCanBeClosedWithoutResponse() {
        for (String message : new String[]{
                "Вам спасибо",
                "И вам спасибо!",
                "Взаимно",
                "Хорошо спасибо большое"
        }) {
            var assessment = policy.assess(message);

            assertFalse(assessment.responseRequired(), message);
            assertTrue(assessment.safeNoResponse(), message);
        }
    }

    @Test
    void acknowledgementWithAdditionalRequestStillRequiresReview() {
        var assessment = policy.assess("Хорошо, спасибо, пришлите чек");

        assertTrue(assessment.responseRequired());
        assertFalse(assessment.safeNoResponse());
    }

    @Test
    void attachmentRequiresReview() {
        var assessment = policy.assess("[Вложение: image]");

        assertTrue(assessment.responseRequired());
        assertTrue(assessment.actionRequired());
    }

    @Test
    void captionedAttachmentIsNeverACourtesyMessage() {
        for (String message : new String[]{
                "[Вложение: image] Спасибо 🙏",
                "[Вложение: photo] Спасибо большое",
                "[Вложение: document] Ок",
                "[Вложение]",
                "[Вложение] Хорошо",
                "[image]"
        }) {
            var assessment = policy.assess(message);

            assertEquals("ATTACHMENT_REQUIRES_REVIEW", assessment.reasonCode(), message);
            assertTrue(assessment.responseRequired(), message);
            assertFalse(assessment.safeNoResponse(), message);
            assertTrue(policy.rejectsNoResponse(assessment), message);
        }
    }

    @Test
    void fileNamesOfCommonFormatsRequireReviewLikePdf() {
        for (String message : new String[]{
                "Документ-2026-07-26 180739.pdf",
                "Счёт на оплату.xlsx",
                "Договор.docx",
                "чек.jpg",
                "Скрин оплаты.png",
                "Документы.zip"
        }) {
            assertEquals("ATTACHMENT_REQUIRES_REVIEW", policy.assess(message).reasonCode(), message);
        }
    }
}
