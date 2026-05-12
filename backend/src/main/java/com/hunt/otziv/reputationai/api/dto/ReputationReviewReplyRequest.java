package com.hunt.otziv.reputationai.api.dto;

public record ReputationReviewReplyRequest(
        String reviewText,
        String tone,
        Integer count
) {
}
