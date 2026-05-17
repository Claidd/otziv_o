package com.hunt.otziv.reputationai.domain;

import java.time.LocalDateTime;
import java.util.List;

public record ReputationBatchReviewDraftResult(
        Long companyId,
        String companyName,
        Long deepReportJobId,
        Long contentPackJobId,
        String provider,
        String model,
        List<ReputationBatchReviewDraftItem> drafts,
        List<String> safetyNotes,
        LocalDateTime generatedAt
) {
    public ReputationBatchReviewDraftResult {
        companyName = normalize(companyName);
        provider = normalize(provider);
        model = normalize(model);
        drafts = drafts == null ? List.of() : drafts.stream()
                .filter(item -> item != null && item.reviewId() != null && !item.draft().isBlank())
                .toList();
        safetyNotes = safetyNotes == null ? List.of() : safetyNotes.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        generatedAt = generatedAt == null ? LocalDateTime.now() : generatedAt;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
