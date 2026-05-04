package com.hunt.otziv.r_review.bot;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.config.email.EmailService;
import com.hunt.otziv.p_products.services.service.BotAssignmentService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewBotChangeServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private BotService botService;

    @Mock
    private EmailService emailService;

    @Mock
    private BotAssignmentService botAssignmentService;

    @Mock
    private FilialService filialService;

    @Test
    void changeBotAssignsNewBotAndUpdatesVigulByCounter() {
        ReviewBotChangeService service = service();
        Review review = new Review();
        review.setVigul(true);
        Bot selectedBot = bot(7L, "Иван Петров", 2);

        when(reviewRepository.findById(15L)).thenReturn(Optional.of(review));
        when(botAssignmentService.assignBotForReviewChange(same(review), eq(Set.of())))
                .thenReturn(selectedBot);

        service.changeBot(15L);

        assertSame(selectedBot, review.getBot());
        assertFalse(review.isVigul());
        verify(reviewRepository).save(review);
    }

    @Test
    void deActivateAndChangeBotUsesCurrentBotWhenRequestBotIdIsMissing() {
        ReviewBotChangeService service = service();
        City city = city(9L, "Иркутск");
        Filial filial = filial(3L, city);
        Bot currentBot = bot(5L, "Старый Бот", 0);
        currentBot.setActive(true);
        Bot selectedBot = bot(8L, "Иван Петров", 4);
        Review review = new Review();
        review.setFilial(filial);
        review.setBot(currentBot);
        review.setVigul(false);

        when(reviewRepository.findById(21L)).thenReturn(Optional.of(review));
        when(botService.getFindAllByFilialCityId(9L)).thenReturn(List.of(currentBot));
        when(botService.findBotById(5L)).thenReturn(currentBot);
        when(botAssignmentService.assignBotForReviewChange(same(review), eq(Set.of(5L))))
                .thenReturn(selectedBot);

        service.deActivateAndChangeBot(21L, null);

        assertFalse(currentBot.isActive());
        assertSame(selectedBot, review.getBot());
        assertTrue(review.isVigul());
        verify(botService).save(currentBot);
        verify(emailService).sendSimpleEmail(
                eq("o-company-server@mail.ru"),
                eq("Мало аккаунтов у города"),
                contains("Иркутск")
        );
        verify(reviewRepository).save(review);
    }

    @Test
    void findAllBotsMinusFilialPrefersTemplateBotsForNotVigulReview() {
        ReviewBotChangeService service = service();
        City city = city(4L, "Иркутск");
        Filial filial = filial(11L, city);
        Review review = new Review();
        review.setId(100L);
        review.setFilial(filial);
        review.setVigul(false);

        Bot templateBot = bot(31L, "Впишите Имя Фамилию", 0);
        Bot regularBot = bot(32L, "Иван Петров", 0);
        Bot usedBot = bot(33L, "Петр Иванов", 0);
        Review existingReview = new Review();
        existingReview.setId(99L);
        existingReview.setBot(usedBot);

        when(botService.getFindAllByFilialCityId(4L))
                .thenReturn(List.of(templateBot, regularBot, usedBot));
        when(reviewRepository.findAllByFilial(filial)).thenReturn(List.of(existingReview));
        when(filialService.findByCityId(4L)).thenReturn(List.of(filial));

        List<Bot> bots = service.findAllBotsMinusFilial(review);

        assertEquals(List.of(templateBot), bots);
    }

    @Test
    void changeBotClearsVigulWhenAssignmentReturnsNull() {
        ReviewBotChangeService service = service();
        Review review = new Review();
        review.setVigul(true);

        when(reviewRepository.findById(16L)).thenReturn(Optional.of(review));
        when(botAssignmentService.assignBotForReviewChange(same(review), anyCollection()))
                .thenReturn(null);

        service.changeBot(16L);

        assertFalse(review.isVigul());
        verify(reviewRepository).save(review);
    }

    private ReviewBotChangeService service() {
        return new ReviewBotChangeService(
                reviewRepository,
                botService,
                emailService,
                botAssignmentService,
                filialService
        );
    }

    private Bot bot(Long id, String fio, int counter) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setFio(fio);
        bot.setCounter(counter);
        bot.setStatus(newStatus());
        return bot;
    }

    private StatusBot newStatus() {
        StatusBot status = new StatusBot();
        status.setBotStatusTitle("Новый");
        return status;
    }

    private City city(Long id, String title) {
        City city = new City();
        city.setId(id);
        city.setTitle(title);
        return city;
    }

    private Filial filial(Long id, City city) {
        Filial filial = new Filial();
        filial.setId(id);
        filial.setCity(city);
        return filial;
    }
}
