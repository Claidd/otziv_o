package com.hunt.otziv.r_review.nagul;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.exceptions.BotTemplateNameException;
import com.hunt.otziv.exceptions.NagulTooFastException;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.model.Worker;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewNagulPolicyTest {

    private final ReviewNagulPolicy policy = new ReviewNagulPolicy();

    @Test
    void validateBotNameAllowsRegularRussianName() {
        Review review = reviewWithBotName("Иван Петров");

        assertDoesNotThrow(() -> policy.validateBotName(review));
    }

    @Test
    void validateBotNameRejectsDigits() {
        Review review = reviewWithBotName("Иван Петров2");

        BotTemplateNameException exception = assertThrows(
                BotTemplateNameException.class,
                () -> policy.validateBotName(review)
        );

        assertEquals("Аккаунт не выгулян: имя содержит цифры", exception.getMessage());
    }

    @Test
    void validateBotNameRejectsTemplateWords() {
        Review review = reviewWithBotName("Имя Петров");

        BotTemplateNameException exception = assertThrows(
                BotTemplateNameException.class,
                () -> policy.validateBotName(review)
        );

        assertEquals("Аккаунт не выгулян: используется шаблонное имя", exception.getMessage());
    }

    @Test
    void validateWorkerCooldownThrowsWhenLastNagulIsInsideCooldown() {
        Worker worker = new Worker();
        worker.setLastNagulTime(LocalDateTime.now().minusMinutes(1));

        NagulTooFastException exception = assertThrows(
                NagulTooFastException.class,
                () -> policy.validateWorkerCooldown(worker, 10)
        );

        assertTrue(exception.getMinutesLeft() >= 8);
        assertTrue(exception.getMinutesLeft() <= 9);
    }

    @Test
    void validateWorkerCooldownAllowsOldNagulTime() {
        Worker worker = new Worker();
        worker.setLastNagulTime(LocalDateTime.now().minusMinutes(20));

        assertDoesNotThrow(() -> policy.validateWorkerCooldown(worker, 10));
    }

    private Review reviewWithBotName(String botName) {
        Bot bot = new Bot();
        bot.setFio(botName);

        Review review = new Review();
        review.setBot(bot);
        return review;
    }
}
