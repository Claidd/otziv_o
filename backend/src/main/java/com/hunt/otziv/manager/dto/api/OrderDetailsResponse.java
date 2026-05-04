package com.hunt.otziv.manager.dto.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderDetailsResponse(
        Long orderId,
        Long companyId,
        UUID orderDetailsId,
        String title,
        String companyTitle,
        String productTitle,
        String status,
        Integer amount,
        Integer counter,
        BigDecimal sum,
        BigDecimal totalSumWithBadReviews,
        BadReviewSummaryResponse badReviewSummary,
        String orderComments,
        String companyComments,
        String created,
        String changed,
        List<ReviewDetailsResponse> reviews,
        List<BadReviewTaskDetailsResponse> badReviewTasks,
        List<ProductOptionResponse> products,
        boolean canEditReviews,
        boolean canSendToCheck,
        boolean canEditReviewDates,
        boolean canEditReviewPublish,
        boolean canEditReviewVigul,
        boolean canDeleteReviews
) {
}
