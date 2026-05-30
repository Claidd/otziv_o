package com.hunt.otziv.r_review.utils;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.r_review.model.Review;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewBotPolicyTest {

    @Test
    void usablePublicationBotRejectsStubAndTemplateAccounts() {
        assertFalse(ReviewBotPolicy.hasUsablePublicationBot((Review) null));
        assertFalse(ReviewBotPolicy.hasUsablePublicationBot(review(null)));
        assertFalse(ReviewBotPolicy.hasUsablePublicationBot(review(bot(1L, "Реальный аккаунт", "login", true))));
        assertFalse(ReviewBotPolicy.hasUsablePublicationBot(review(bot(10L, "Впиши Имя Фамилию", "login", true))));
        assertFalse(ReviewBotPolicy.hasUsablePublicationBot(review(bot(11L, "Нет доступных аккаунтов", "login", true))));
    }

    @Test
    void usablePublicationBotAcceptsActiveRealAccountWithLogin() {
        assertTrue(ReviewBotPolicy.hasUsablePublicationBot(review(bot(12L, "Анна Иванова", "login", true))));
    }

    private Review review(Bot bot) {
        Review review = new Review();
        review.setBot(bot);
        return review;
    }

    private Bot bot(Long id, String fio, String login, boolean active) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setFio(fio);
        bot.setLogin(login);
        bot.setActive(active);
        return bot;
    }
}
