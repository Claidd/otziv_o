package com.hunt.otziv.r_review.bot.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.utils.ReviewBotPolicy;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ReviewAccountWalkScheduleService {

    private static final int DEFAULT_WALKED_COUNTER_THRESHOLD = 2;
    private static final int DEFAULT_WALK_DELAY_DAYS = 2;
    private static final Long STUB_BOT_ID = 1L;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Irkutsk");

    private final ReviewRepository reviewRepository;
    private final AppSettingService appSettingService;
    private final BusinessAuditService businessAuditService;
    private final Clock clock;

    @Autowired
    public ReviewAccountWalkScheduleService(
            ReviewRepository reviewRepository,
            AppSettingService appSettingService,
            BusinessAuditService businessAuditService
    ) {
        this(reviewRepository, appSettingService, businessAuditService, Clock.system(BUSINESS_ZONE));
    }

    ReviewAccountWalkScheduleService(
            ReviewRepository reviewRepository,
            AppSettingService appSettingService,
            BusinessAuditService businessAuditService,
            Clock clock
    ) {
        this.reviewRepository = reviewRepository;
        this.appSettingService = appSettingService;
        this.businessAuditService = businessAuditService;
        this.clock = clock;
    }

    public boolean isWalkedAccount(Bot bot) {
        return ReviewBotPolicy.isWalkedAccount(bot, walkedCounterThreshold());
    }

    public boolean isEligibleForNagul(Bot bot) {
        return ReviewBotPolicy.isEligibleForNagul(bot, walkedCounterThreshold());
    }

    /**
     * Reconciles the review with the account that is currently assigned to it.
     *
     * <p>The result intentionally does not depend on the previous review flag or on UI source metadata.
     * Every assignment path can safely call this method. A repeated check of the same account keeps
     * the original deadline; a genuinely new unwalked account only extends the schedule up to
     * {@code assignment day + configured delay}, never by blindly stacking another delay.</p>
     */
    public void synchronizeAfterAccountChange(Review review) {
        if (review == null) {
            return;
        }

        boolean newWalked = isWalkedAccount(review.getBot());
        review.setVigul(newWalked);

        if (review.isPublish() || !hasRealAccount(review)) {
            return;
        }

        if (!newWalked) {
            ensureWalkWindow(review, walkDelayDays());
            return;
        }

        // Completing a walk or assigning an already walked account must not pull the publication
        // date back. The reserved window remains part of the agreed publication schedule.
        review.setAccountWalkDelayBotId(null);
        review.setAccountWalkNotBefore(null);
        review.setAccountWalkDelayDays(0);
    }

    public LocalDate minimumPublicationDateForCurrentAccount(Review review) {
        if (review == null || review.isPublish() || !hasRealAccount(review) || isWalkedAccount(review.getBot())) {
            return null;
        }

        Long botId = botId(review);
        if (Objects.equals(review.getAccountWalkDelayBotId(), botId)
                && review.getAccountWalkNotBefore() != null) {
            return review.getAccountWalkNotBefore();
        }
        return shiftDate(LocalDate.now(clock), walkDelayDays());
    }

    private void ensureWalkWindow(Review triggerReview, int delayDays) {
        if (delayDays <= 0 || triggerReview.getPublishedDate() == null) {
            return;
        }

        Long assignedBotId = botId(triggerReview);
        boolean sameAssignment = Objects.equals(triggerReview.getAccountWalkDelayBotId(), assignedBotId)
                && triggerReview.getAccountWalkNotBefore() != null;
        LocalDate requiredDate = sameAssignment
                ? triggerReview.getAccountWalkNotBefore()
                : shiftDate(LocalDate.now(clock), delayDays);
        triggerReview.setAccountWalkDelayBotId(assignedBotId);
        triggerReview.setAccountWalkNotBefore(requiredDate);

        Long orderId = orderId(triggerReview);
        Long triggerReviewId = triggerReview.getId();
        LocalDate oldTriggerDate = triggerReview.getPublishedDate();
        if (!oldTriggerDate.isBefore(requiredDate)) {
            recordScheduleDecision(triggerReview, oldTriggerDate, oldTriggerDate, requiredDate, sameAssignment, 0);
            return;
        }
        if (orderId == null || triggerReviewId == null) {
            moveToDate(triggerReview, requiredDate);
            recordScheduleDecision(triggerReview, oldTriggerDate, requiredDate, requiredDate, sameAssignment, 1);
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
                moveToDate(review, requiredDate);
                previousDate = review.getPublishedDate();
                shiftedCount++;
                continue;
            }

            LocalDate followingRequiredDate = previousDate == null ? null : shiftDate(previousDate, delayDays);
            if (followingRequiredDate != null && review.getPublishedDate().isBefore(followingRequiredDate)) {
                moveToDate(review, followingRequiredDate);
                shiftedCount++;
            }
            previousDate = review.getPublishedDate();
        }

        if (shiftedCount > 0) {
            reviewRepository.saveAll(orderReviews);
            log.info(
                    "Publication dates delayed after unwalked account assignment: orderId={}, triggerReviewId={}, requiredDate={}, shiftedCount={}",
                    orderId,
                    triggerReviewId,
                    requiredDate,
                    shiftedCount
            );
        }
        recordScheduleDecision(
                triggerReview,
                oldTriggerDate,
                triggerReview.getPublishedDate(),
                requiredDate,
                sameAssignment,
                shiftedCount
        );
    }

    private void moveToDate(Review review, LocalDate targetDate) {
        int actualDelta = Math.toIntExact(daysBetween(review.getPublishedDate(), targetDate));
        review.setPublishedDate(targetDate);
        review.setAccountWalkDelayDays(Math.max(0, review.getAccountWalkDelayDays()) + actualDelta);
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

    private Long botId(Review review) {
        Bot bot = review != null ? review.getBot() : null;
        return bot != null ? bot.getId() : null;
    }

    private boolean hasRealAccount(Review review) {
        Long botId = botId(review);
        return botId != null && botId > 0 && !STUB_BOT_ID.equals(botId);
    }

    private void recordScheduleDecision(
            Review review,
            LocalDate oldDate,
            LocalDate newDate,
            LocalDate requiredDate,
            boolean sameAssignment,
            int shiftedCount
    ) {
        businessAuditService.recordSafely(
                "review_account_walk_schedule_checked",
                "review",
                review.getId(),
                orderId(review),
                review.getId(),
                oldDate,
                newDate,
                "botId=" + botId(review)
                        + ", counter=" + (review.getBot() != null ? review.getBot().getCounter() : null)
                        + ", requiredDate=" + requiredDate
                        + ", sameAssignment=" + sameAssignment
                        + ", shiftedReviews=" + shiftedCount
        );
    }

    private Long orderId(Review review) {
        OrderDetails details = review.getOrderDetails();
        Order order = details != null ? details.getOrder() : null;
        return order != null ? order.getId() : null;
    }

    public int walkedCounterThreshold() {
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
