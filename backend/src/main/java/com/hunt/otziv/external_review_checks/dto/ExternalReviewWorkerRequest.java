package com.hunt.otziv.external_review_checks.dto;

public record ExternalReviewWorkerRequest(
        Long checkId,
        Long reviewId,
        String platform,
        String filialUrl,
        String expectedText
) {
}
