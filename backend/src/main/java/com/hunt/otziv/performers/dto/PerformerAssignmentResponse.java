package com.hunt.otziv.performers.dto;

import java.math.BigDecimal;

public record PerformerAssignmentResponse(
        Long id,
        Long orderId,
        Long reviewId,
        Long offerId,
        String companyTitle,
        String filialTitle,
        String cityTitle,
        String platform,
        String status,
        String draftText,
        String finalText,
        String instruction,
        String publicationUrl,
        String acceptedAt,
        String walkedAt,
        String publishAvailableAt,
        String publishedClaimedAt,
        String verifiedAt,
        BigDecimal payoutAmount
) {
}
