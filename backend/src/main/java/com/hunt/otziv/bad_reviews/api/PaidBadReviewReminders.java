package com.hunt.otziv.bad_reviews.api;

/** Orders may close the review-ready reminder only after a paid order commits. */
public interface PaidBadReviewReminders {
    /** Best-effort cleanup in its own transaction; never changes payment state. */
    void closeForPaidOrder(Long orderId);
}
