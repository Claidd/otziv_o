package com.hunt.otziv.external_review_checks.dto;

public record ExternalReviewWorkerResponse(
        Long checkId,
        String status,
        Double confidence,
        String matchedTextExcerpt,
        String screenshotBase64,
        String screenshotContentType,
        String errorMessage,
        String traceId
) {
}
