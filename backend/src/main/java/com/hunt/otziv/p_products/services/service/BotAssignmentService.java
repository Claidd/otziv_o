package com.hunt.otziv.p_products.services.service;


import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.r_review.model.Review;

import java.util.List;


public interface BotAssignmentService {

    /**
     * Назначение ботов для новых отзывов
     */
    List<Review> assignBotsToNewReviews(OrderDTO orderDTO, OrderDetails orderDetails);

    /**
     * Назначение ботов существующим отзывам с null ботом
     */
    boolean assignBotsToExistingReviews(List<Review> reviews, Filial filial);

    /**
     * Получение доступных ботов по правилам
     */
    List<Bot> getAvailableBotsByRules(Filial filial, boolean vigul, int neededForOrder);

    /**
     * Проверка наличия ботов-заглушек в списке отзывов
     */
    void checkAndNotifyAboutStubBots(List<Review> reviews);

    /**
     * Обновление isVigul отзыва на основе counter бота
     */
    void updateReviewVigulBasedOnBotCounter(Review review, Bot bot);
}
