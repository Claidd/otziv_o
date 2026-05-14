package com.hunt.otziv.reputationai.api.dto;

public record ReputationReviewTemplatesRequest(
        Long deepReportJobId,
        Long contentPackJobId,
        String manualNotes,
        Integer topicsCount,
        Integer draftsCount,
        String tone,
        String contentPackProfile
) {
    public ReputationReviewTemplatesRequest {
        manualNotes = manualNotes == null ? "" : manualNotes.trim();
        tone = tone == null || tone.isBlank() ? "естественный, рекламно-полезный, без фейкового опыта" : tone.trim();
        contentPackProfile = contentPackProfile == null || contentPackProfile.isBlank() ? "quality" : contentPackProfile.trim();
        topicsCount = clamp(topicsCount, 4, 10, 7);
        draftsCount = clamp(draftsCount, 3, 8, 5);
    }

    private static int clamp(Integer value, int min, int max, int fallback) {
        int actual = value == null ? fallback : value;
        return Math.max(min, Math.min(max, actual));
    }
}
