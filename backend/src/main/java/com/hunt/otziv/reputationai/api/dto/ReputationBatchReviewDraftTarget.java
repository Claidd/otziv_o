package com.hunt.otziv.reputationai.api.dto;

public record ReputationBatchReviewDraftTarget(
        Long reviewId,
        String idea,
        String previousDraft,
        String orderContext
) {
    public ReputationBatchReviewDraftTarget {
        idea = idea == null ? "" : idea.trim();
        previousDraft = previousDraft == null ? "" : previousDraft.trim();
        orderContext = orderContext == null ? "" : orderContext.trim();
    }
}
