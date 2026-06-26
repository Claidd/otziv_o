package com.hunt.otziv.p_products.status;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.services.service.BotAssignmentService;
import com.hunt.otziv.r_review.bot.ReviewBotCooldownService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.hunt.otziv.p_products.utils.OrderReviewGraph.getAllReviews;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.hasDetails;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderBotLifecycleService {

    private static final int MEDIUM_COUNTER_THRESHOLD = 10;
    private static final int HIGH_COUNTER_THRESHOLD = 20;
    private static final String MEDIUM_STATUS = "Средний";
    private static final String HIGH_STATUS = "Высокий";

    private final BotAssignmentService botAssignmentService;
    private final BotService botService;
    private final ReviewService reviewService;
    private final BusinessAuditService businessAuditService;
    private final ReviewBotCooldownService botCooldownService;

    public void assignBotsIfNeeded(Order order) {
        try {
            if (!hasDetails(order)) {
                log.warn("У заказа ID {} нет OrderDetails", order != null ? order.getId() : null);
                return;
            }

            List<Review> reviews = getAllReviews(order);
            if (reviews.isEmpty()) {
                return;
            }

            long nullBotCount = reviews.stream()
                    .filter(review -> review.getBot() == null)
                    .count();

            if (nullBotCount > 0) {
                log.info("Найдено {} отзывов без ботов в заказе ID {}, назначаем...",
                        nullBotCount, order.getId());

                boolean botsAssigned = botAssignmentService.assignBotsToExistingReviews(
                        reviews, order.getFilial());

                if (botsAssigned) {
                    log.info("Боты успешно назначены для {} отзывов", nullBotCount);
                } else {
                    log.warn("Не удалось назначить боты для отзывов");
                }
            }

            botAssignmentService.checkAndNotifyAboutStubBots(reviews);

        } catch (Exception e) {
            log.error("Ошибка при проверке/назначении ботов: {}", e.getMessage(), e);
        }
    }

    public void checkAndNotifyAboutStubBots(List<Review> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return;
        }

        botAssignmentService.checkAndNotifyAboutStubBots(reviews);
    }

    public void detachBots(Order order) {
        List<Review> reviews = getAllReviews(order);
        if (reviews.isEmpty()) {
            return;
        }

        log.info("Отвязываем ботов от {} отзывов", reviews.size());
        for (Review review : reviews) {
            if (review.getBot() != null) {
                log.debug("Отвязываем бота ID: {} от отзыва ID: {}",
                        review.getBot().getId(), review.getId());
                botCooldownService.markReleased(review.getBot(), "order bot detached");
            }
            review.setBot(null);
            reviewService.save(review);
        }
    }

    public void updateBotCounterAndStatus(Bot bot) {
        try {
            if (bot == null) {
                return;
            }

            boolean oldActive = bot.isActive();
            int currentCounter = bot.getCounter();
            bot.setCounter(currentCounter + 1);

            if (bot.getCounter() >= HIGH_COUNTER_THRESHOLD) {
                bot.setStatus(botService.changeStatus(HIGH_STATUS));
                bot.setActive(false);
                auditActiveChange(bot, oldActive, false, "auto disabled after high publication counter");
            } else if (bot.getCounter() >= MEDIUM_COUNTER_THRESHOLD) {
                bot.setStatus(botService.changeStatus(MEDIUM_STATUS));
                bot.setActive(false);
                auditActiveChange(bot, oldActive, false, "auto disabled after medium publication counter");
            }

            botService.save(bot);
        } catch (Exception e) {
            log.error("Ошибка при обновлении бота id={}", bot != null ? bot.getId() : null, e);
            throw e;
        }
    }

    private void auditActiveChange(Bot bot, boolean oldActive, boolean newActive, String details) {
        if (oldActive == newActive || bot == null || bot.getId() == null) {
            return;
        }

        businessAuditService.recordSafely(
                "bot_active_changed",
                "bot",
                bot.getId(),
                null,
                null,
                oldActive,
                newActive,
                details
        );
    }
}
