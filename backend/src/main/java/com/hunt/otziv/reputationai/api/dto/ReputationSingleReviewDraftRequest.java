package com.hunt.otziv.reputationai.api.dto;

public record ReputationSingleReviewDraftRequest(
        Long deepReportJobId,
        Long contentPackJobId,
        String idea,
        String style,
        String authorType,
        String emojiMode,
        String manualNotes,
        String length,
        String contentPackProfile,
        Long targetReviewId,
        String previousDraft,
        String orderContext
) {
    public ReputationSingleReviewDraftRequest {
        idea = idea == null ? "" : idea.trim();
        style = style == null || style.isBlank()
                ? "живой, спокойный, с мягкой рекламной пользой"
                : style.trim();
        authorType = authorType == null || authorType.isBlank()
                ? "нейтральный клиент"
                : authorType.trim();
        emojiMode = emojiMode == null || emojiMode.isBlank()
                ? "без смайлов"
                : emojiMode.trim();
        manualNotes = manualNotes == null ? "" : manualNotes.trim();
        length = length == null || length.isBlank() ? "medium" : length.trim();
        contentPackProfile = contentPackProfile == null || contentPackProfile.isBlank()
                ? "quality"
                : contentPackProfile.trim();
        previousDraft = previousDraft == null ? "" : previousDraft.trim();
        orderContext = orderContext == null ? "" : orderContext.trim();
    }

    public ReputationSingleReviewDraftRequest(
            Long deepReportJobId,
            Long contentPackJobId,
            String idea,
            String style,
            String manualNotes,
            String length,
            String contentPackProfile
    ) {
        this(deepReportJobId, contentPackJobId, idea, style, null, null, manualNotes, length, contentPackProfile, null, null, null);
    }

    public ReputationSingleReviewDraftRequest withOrderContext(String orderContext) {
        return new ReputationSingleReviewDraftRequest(
                deepReportJobId,
                contentPackJobId,
                idea,
                style,
                authorType,
                emojiMode,
                manualNotes,
                length,
                contentPackProfile,
                targetReviewId,
                previousDraft,
                orderContext
        );
    }
}
