package com.hunt.otziv.reputationai.api.dto;

public record ReputationSingleReviewDraftRequest(
        Long deepReportJobId,
        Long contentPackJobId,
        String idea,
        String style,
        String manualNotes,
        String length,
        String contentPackProfile
) {
    public ReputationSingleReviewDraftRequest {
        idea = idea == null ? "" : idea.trim();
        style = style == null || style.isBlank()
                ? "живой, спокойный, с мягкой рекламной пользой"
                : style.trim();
        manualNotes = manualNotes == null ? "" : manualNotes.trim();
        length = length == null || length.isBlank() ? "medium" : length.trim();
        contentPackProfile = contentPackProfile == null || contentPackProfile.isBlank()
                ? "quality"
                : contentPackProfile.trim();
    }
}
