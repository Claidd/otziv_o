package com.hunt.otziv.external_review_checks.dto;

public record ExternalReviewCheckStatusResponse(
        boolean enabled,
        boolean hardEnabled,
        boolean operatorEnabled
) {
}
