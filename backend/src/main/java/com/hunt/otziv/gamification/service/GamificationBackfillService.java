package com.hunt.otziv.gamification.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.gamification.dto.GamificationBackfillResponse;
import com.hunt.otziv.gamification.dto.GamificationScoreLedgerRebuildResponse;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryTaskRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GamificationBackfillService {

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final BadReviewTaskRepository badReviewTaskRepository;
    private final ReviewRecoveryTaskRepository reviewRecoveryTaskRepository;
    private final GamificationEventService eventService;
    private final GamificationShadowScoreService shadowScoreService;

    @Transactional
    public GamificationBackfillResponse backfill(int days) {
        Period period = period(days);

        List<Review> publishedReviews = reviewRepository.findPublishedForGamificationBackfill(period.from(), period.to());
        long reviewEvents = publishedReviews.stream()
                .filter(eventService::backfillReviewPublished)
                .count();

        List<Order> paidOrders = orderRepository.findPaidForGamificationBackfill(period.from(), period.to());
        long orderEvents = paidOrders.stream()
                .filter(eventService::backfillOrderPaid)
                .count();

        List<BadReviewTask> badReviewTasks = badReviewTaskRepository.findDoneForGamificationBackfill(
                BadReviewTaskStatus.DONE,
                period.from(),
                period.to()
        );
        long badReviewEvents = badReviewTasks.stream()
                .filter(eventService::backfillBadReviewTaskDone)
                .count();

        List<ReviewRecoveryTask> recoveryTasks = reviewRecoveryTaskRepository.findDoneForGamificationBackfill(
                ReviewRecoveryTaskStatus.DONE,
                period.from(),
                period.to()
        );
        long recoveryEvents = recoveryTasks.stream()
                .filter(eventService::backfillReviewRecoveryTaskDone)
                .count();

        long reviewed = (long) publishedReviews.size() + paidOrders.size() + badReviewTasks.size() + recoveryTasks.size();
        long created = reviewEvents + orderEvents + badReviewEvents + recoveryEvents;
        GamificationScoreLedgerRebuildResponse ledgerRebuild = shadowScoreService.rebuild(period.days());

        return new GamificationBackfillResponse(
                period.from(),
                period.to(),
                period.days(),
                reviewed,
                created,
                publishedReviews.size(),
                reviewEvents,
                paidOrders.size(),
                orderEvents,
                badReviewTasks.size(),
                badReviewEvents,
                recoveryTasks.size(),
                recoveryEvents,
                ledgerRebuild
        );
    }

    private Period period(int days) {
        int safeDays = Math.max(1, Math.min(days, 30));
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(safeDays - 1L);
        return new Period(from, to, safeDays);
    }

    private record Period(LocalDate from, LocalDate to, int days) {
    }
}
