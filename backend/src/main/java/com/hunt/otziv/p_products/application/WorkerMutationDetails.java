package com.hunt.otziv.p_products.application;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import org.springframework.security.core.Authentication;
import java.security.Principal;
import com.hunt.otziv.p_products.service.WorkerLegacyFailureAdapter;

public final class WorkerMutationDetails {
    private WorkerMutationDetails() {}
    public static String safe(String value) {
        return value == null ? "" : value;
    }

    public static String valueOrDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    public static String principalName(Principal principal) {
        return principal == null ? "unknown" : safe(principal.getName());
    }

    public static String botDetails(Bot bot) {
        return "botId=" + valueOrDash(bot == null ? null : bot.getId()) + ";";
    }

    public static String botChangeDetails(Long oldBotId, Long newBotId) {
        return "oldBotId=" + valueOrDash(oldBotId)
                + ";newBotId=" + valueOrDash(newBotId)
                + ";botId=" + valueOrDash(newBotId)
                + ";";
    }

    public static Long botId(Review review) {
        Bot bot = review != null ? review.getBot() : null;
        return bot != null ? bot.getId() : null;
    }

    public static Long botId(BadReviewTask task) {
        Bot bot = task != null ? task.getBot() : null;
        return bot != null ? bot.getId() : null;
    }

    public static Long botId(ReviewRecoveryTask task) {
        Bot bot = task != null ? task.getBot() : null;
        return bot != null ? bot.getId() : null;
    }

    public static Long orderId(Review review) {
        Order order = review != null && review.getOrderDetails() != null
                ? review.getOrderDetails().getOrder()
                : null;
        return order != null ? order.getId() : null;
    }

    public static Long orderId(BadReviewTask task) {
        Order order = task != null ? task.getOrder() : null;
        return order != null ? order.getId() : null;
    }

    public static Long orderId(ReviewRecoveryTask task) {
        Order order = task != null ? task.getOrder() : null;
        return order != null ? order.getId() : task != null ? task.getArchiveOrderId() : null;
    }

    public static Long reviewId(BadReviewTask task) {
        Review review = task != null ? task.getSourceReview() : null;
        return review != null ? review.getId() : null;
    }

    public static Long reviewId(ReviewRecoveryTask task) {
        Review review = task != null ? task.getSourceReview() : null;
        return review != null ? review.getId() : task != null ? task.getArchiveReviewId() : null;
    }
    public static Authentication requireActor(WorkerOrderActor actor, String... roles) {
        if (actor == null) throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.FORBIDDEN,"Операция недоступна");
        actor.require(roles);
        return actor.authentication();
    }

    public static WorkerOrderCommandException commandFailure(Exception failure, String fallbackMessage) {
        if (failure instanceof WorkerOrderCommandException commandFailure) return commandFailure;
        var legacy = WorkerLegacyFailureAdapter.describe(failure);
        if (legacy != null) return WorkerOrderCommandException.legacy(legacy.statusCode(), legacy.message(), failure);
        return new WorkerOrderCommandException(WorkerOrderCommandException.Kind.BAD_REQUEST, fallbackMessage, failure);
    }

}
