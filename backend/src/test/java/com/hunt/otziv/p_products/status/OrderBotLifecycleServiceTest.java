package com.hunt.otziv.p_products.status;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.services.service.BotAssignmentService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderBotLifecycleServiceTest {

    @Mock
    private BotAssignmentService botAssignmentService;

    @Mock
    private BotService botService;

    @Mock
    private ReviewService reviewService;

    @Test
    void assignBotsIfNeededAssignsOnlyWhenReviewsHaveMissingBots() {
        OrderBotLifecycleService service = service();
        Filial filial = new Filial();
        Review missingBot = review(1L, null);
        Review existingBot = review(2L, bot(20L, 3));
        List<Review> reviews = List.of(missingBot, existingBot);
        Order order = order(10L, filial, reviews);

        when(botAssignmentService.assignBotsToExistingReviews(reviews, filial)).thenReturn(true);

        service.assignBotsIfNeeded(order);

        verify(botAssignmentService).assignBotsToExistingReviews(reviews, filial);
        verify(botAssignmentService).checkAndNotifyAboutStubBots(reviews);
        verifyNoInteractions(botService, reviewService);
    }

    @Test
    void assignBotsIfNeededDoesNothingWhenOrderHasNoDetails() {
        OrderBotLifecycleService service = service();

        service.assignBotsIfNeeded(new Order());

        verifyNoInteractions(botAssignmentService, botService, reviewService);
    }

    @Test
    void checkAndNotifyAboutStubBotsSkipsEmptyReviews() {
        OrderBotLifecycleService service = service();

        service.checkAndNotifyAboutStubBots(List.of());

        verifyNoInteractions(botAssignmentService, botService, reviewService);
    }

    @Test
    void detachBotsClearsBotsAndSavesEveryReview() {
        OrderBotLifecycleService service = service();
        Review first = review(1L, bot(10L, 1));
        Review second = review(2L, null);
        Order order = order(11L, new Filial(), List.of(first, second));

        service.detachBots(order);

        assertNull(first.getBot());
        assertNull(second.getBot());
        verify(reviewService).save(first);
        verify(reviewService).save(second);
        verifyNoInteractions(botAssignmentService, botService);
    }

    @Test
    void updateBotCounterAndStatusSetsMediumStatusAtThreshold() {
        OrderBotLifecycleService service = service();
        Bot bot = bot(30L, 9);
        StatusBot mediumStatus = status("Средний");

        when(botService.changeStatus("Средний")).thenReturn(mediumStatus);

        service.updateBotCounterAndStatus(bot);

        assertEquals(10, bot.getCounter());
        assertSame(mediumStatus, bot.getStatus());
        assertFalse(bot.isActive());
        verify(botService).save(bot);
    }

    @Test
    void updateBotCounterAndStatusSetsHighStatusAtThreshold() {
        OrderBotLifecycleService service = service();
        Bot bot = bot(31L, 19);
        StatusBot highStatus = status("Высокий");

        when(botService.changeStatus("Высокий")).thenReturn(highStatus);

        service.updateBotCounterAndStatus(bot);

        assertEquals(20, bot.getCounter());
        assertSame(highStatus, bot.getStatus());
        assertFalse(bot.isActive());
        verify(botService).save(bot);
    }

    @Test
    void updateBotCounterAndStatusIgnoresNullBot() {
        OrderBotLifecycleService service = service();

        service.updateBotCounterAndStatus(null);

        verify(botService, never()).save(null);
        verifyNoInteractions(botAssignmentService, reviewService);
    }

    private OrderBotLifecycleService service() {
        return new OrderBotLifecycleService(botAssignmentService, botService, reviewService);
    }

    private Order order(Long id, Filial filial, List<Review> reviews) {
        Order order = new Order();
        order.setId(id);
        order.setFilial(filial);

        OrderDetails detail = new OrderDetails();
        detail.setOrder(order);
        detail.setReviews(reviews);
        order.setDetails(List.of(detail));
        return order;
    }

    private Review review(Long id, Bot bot) {
        Review review = new Review();
        review.setId(id);
        review.setBot(bot);
        return review;
    }

    private Bot bot(Long id, int counter) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setCounter(counter);
        bot.setActive(true);
        return bot;
    }

    private StatusBot status(String title) {
        StatusBot status = new StatusBot();
        status.setBotStatusTitle(title);
        return status;
    }
}
