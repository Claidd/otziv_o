package com.hunt.otziv.r_review.bot;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.utils.ReviewBotPolicy;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewAccountWalkScheduleService {

    private static final int DEFAULT_WALKED_COUNTER_THRESHOLD = 3;
    private static final int DEFAULT_WALK_DELAY_DAYS = 2;

    private final ReviewRepository reviewRepository;
    private final AppSettingService appSettingService;

    public boolean isWalkedAccount(Bot bot) {
        return ReviewBotPolicy.isWalkedAccount(bot, walkedCounterThreshold());
    }

    public void synchronizeAfterAccountChange(Review review, boolean oldWalked) {
        if (review == null) {
            return;
        }

        boolean newWalked = isWalkedAccount(review.getBot());
        review.setVigul(newWalked);

        if (review.isPublish()) {
            return;
        }

        if (oldWalked && !newWalked) {
            applyWalkDelayCascade(review, walkDelayDays());
            return;
        }
        if (!oldWalked && newWalked) {
            removeWalkDelayCascade(review, walkDelayDays());
        }
    }

    private void applyWalkDelayCascade(Review triggerReview, int delayDays) {
        Long orderId = orderId(triggerReview);
        Long triggerReviewId = triggerReview.getId();
        int triggerDelay = Math.max(0, triggerReview.getAccountWalkDelayDays());
        int triggerDelta = Math.max(0, delayDays - triggerDelay);
        if (orderId == null || triggerReviewId == null || triggerDelta == 0) {
            return;
        }

        List<Review> orderReviews = reviewRepository.findAllByOrderIdForAccountWalkSchedule(orderId);
        boolean shiftStarted = false;
        LocalDate previousDate = null;
        int shiftedCount = 0;

        for (Review review : orderReviews) {
            if (Objects.equals(review.getId(), triggerReviewId)) {
                shiftStarted = true;
            }
            if (!shiftStarted || review.isPublish() || review.getPublishedDate() == null) {
                continue;
            }

            if (Objects.equals(review.getId(), triggerReviewId)) {
                applyDelay(review, triggerDelta);
                previousDate = review.getPublishedDate();
                shiftedCount++;
                continue;
            }

            while (previousDate != null && daysBetween(previousDate, review.getPublishedDate()) < delayDays) {
                applyDelay(review, delayDays);
                shiftedCount++;
            }
            previousDate = review.getPublishedDate();
        }

        if (shiftedCount > 0) {
            reviewRepository.saveAll(orderReviews);
            log.info(
                    "Publication dates delayed after unwalked account assignment: orderId={}, triggerReviewId={}, delayDays={}, shiftedCount={}",
                    orderId,
                    triggerReviewId,
                    delayDays,
                    shiftedCount
            );
        }
    }

    private void removeWalkDelayCascade(Review triggerReview, int delayDays) {
        Long orderId = orderId(triggerReview);
        Long triggerReviewId = triggerReview.getId();
        if (orderId == null || triggerReviewId == null || delayDays <= 0) {
            return;
        }

        List<Review> orderReviews = reviewRepository.findAllByOrderIdForAccountWalkSchedule(orderId);
        boolean shiftStarted = false;
        LocalDate previousDate = null;
        int shiftedCount = 0;

        for (Review review : orderReviews) {
            if (Objects.equals(review.getId(), triggerReviewId)) {
                shiftStarted = true;
            }
            if (!shiftStarted || review.isPublish() || review.getPublishedDate() == null) {
                continue;
            }

            int currentDelay = Math.max(0, review.getAccountWalkDelayDays());
            if (currentDelay <= 0) {
                previousDate = review.getPublishedDate();
                continue;
            }

            int removeDays = Math.min(delayDays, currentDelay);
            LocalDate candidateDate = shiftDate(review.getPublishedDate(), -removeDays);
            if (previousDate == null || daysBetween(previousDate, candidateDate) >= delayDays) {
                review.setPublishedDate(candidateDate);
                review.setAccountWalkDelayDays(currentDelay - removeDays);
                shiftedCount++;
            }
            previousDate = review.getPublishedDate();
        }

        if (shiftedCount > 0) {
            reviewRepository.saveAll(orderReviews);
            log.info(
                    "Publication dates restored after walked account assignment: orderId={}, triggerReviewId={}, delayDays={}, shiftedCount={}",
                    orderId,
                    triggerReviewId,
                    delayDays,
                    shiftedCount
            );
        }
    }

    private void applyDelay(Review review, int delayDays) {
        review.setPublishedDate(shiftDate(review.getPublishedDate(), delayDays));
        review.setAccountWalkDelayDays(Math.max(0, review.getAccountWalkDelayDays()) + delayDays);
    }

    private long daysBetween(LocalDate previousDate, LocalDate currentDate) {
        return ChronoUnit.DAYS.between(previousDate, currentDate);
    }

    private LocalDate shiftDate(LocalDate date, int deltaDays) {
        LocalDate shifted = date.plusDays(deltaDays);
        while (shifted.getDayOfWeek() == DayOfWeek.SATURDAY) {
            shifted = shifted.plusDays(deltaDays >= 0 ? 1 : -1);
        }
        return shifted;
    }

    private Long orderId(Review review) {
        OrderDetails details = review.getOrderDetails();
        Order order = details != null ? details.getOrder() : null;
        return order != null ? order.getId() : null;
    }

    private int walkedCounterThreshold() {
        return Math.max(1, appSettingService.getInt(
                AppSettingService.REVIEW_ACCOUNT_WALKED_COUNTER_THRESHOLD,
                DEFAULT_WALKED_COUNTER_THRESHOLD
        ));
    }

    private int walkDelayDays() {
        return Math.max(0, appSettingService.getInt(
                AppSettingService.REVIEW_ACCOUNT_WALK_DELAY_DAYS,
                DEFAULT_WALK_DELAY_DAYS
        ));
    }
}
