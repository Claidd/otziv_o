package com.hunt.otziv.client_chat_control.service;

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
}
