package com.hunt.otziv.bad_reviews.dto;

import java.math.BigDecimal;

public record BadReviewTaskSummary(
        int total,
        int pending,
        int done,
        int canceled,
        BigDecimal doneSum,
        BigDecimal pendingSum
) {
    public BadReviewTaskSummary {
        doneSum = doneSum == null ? BigDecimal.ZERO : doneSum;
        pendingSum = pendingSum == null ? BigDecimal.ZERO : pendingSum;
    }

    public static BadReviewTaskSummary empty() {
        return new BadReviewTaskSummary(0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
