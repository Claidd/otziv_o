package com.hunt.otziv.reputationai.api.dto;

import java.util.List;

public record ReputationBatchReviewDraftRequest(
        Long deepReportJobId,
        Long contentPackJobId,
        String style,
        String authorType,
        String emojiMode,
        String manualNotes,
        String length,
        String contentPackProfile,
        List<ReputationBatchReviewDraftTarget> targets
) {
    public ReputationBatchReviewDraftRequest {
        style = style == null || style.isBlank()
                ? "живой, естественный, без одинаковых заходов и канцелярита"
                : style.trim();
        authorType = authorType == null || authorType.isBlank()
                ? "разные обычные клиенты"
                : authorType.trim();
        emojiMode = emojiMode == null || emojiMode.isBlank()
                ? "без смайлов"
                : emojiMode.trim();
        manualNotes = manualNotes == null ? "" : manualNotes.trim();
        length = length == null || length.isBlank() ? "mixed" : length.trim();
        contentPackProfile = contentPackProfile == null || contentPackProfile.isBlank()
                ? "economy"
                : contentPackProfile.trim();
        targets = targets == null ? List.of() : targets.stream()
                .filter(target -> target != null && target.reviewId() != null)
                .limit(30)
                .toList();
    }
}
