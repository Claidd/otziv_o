package com.hunt.otziv.manager.dto.api;

import java.math.BigDecimal;

public record BadReviewSummaryResponse(
        int total,
        int pending,
        int done,
        int canceled,
        BigDecimal doneSum,
        BigDecimal pendingSum,
        BigDecimal totalSumWithBadReviews
) {
}
