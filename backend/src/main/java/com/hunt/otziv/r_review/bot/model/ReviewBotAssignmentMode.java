package com.hunt.otziv.r_review.bot.model;

import com.hunt.otziv.r_review.model.Review;

public enum ReviewBotAssignmentMode {
    DEFAULT_ORDER_ASSIGNMENT,
    NAGUL_ONLY,
    PUBLISH_PREFER_WALKED;

    public static ReviewBotAssignmentMode forReviewChange(Review review) {
        return review != null && review.isVigul()
                ? PUBLISH_PREFER_WALKED
                : NAGUL_ONLY;
    }
}
