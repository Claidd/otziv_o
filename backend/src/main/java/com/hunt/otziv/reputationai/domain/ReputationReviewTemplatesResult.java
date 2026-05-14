package com.hunt.otziv.reputationai.domain;

import java.time.LocalDateTime;
import java.util.List;

public record ReputationReviewTemplatesResult(
        Long companyId,
        String companyName,
        Long deepReportJobId,
        Long contentPackJobId,
        String provider,
        String model,
        List<String> honestReviewTopics,
        List<String> reviewDraftTemplates,
        List<String> safetyNotes,
        LocalDateTime generatedAt
) {
    public ReputationReviewTemplatesResult {
        companyName = companyName == null ? "" : companyName.trim();
        provider = provider == null ? "" : provider.trim();
        model = model == null ? "" : model.trim();
        honestReviewTopics = clean(honestReviewTopics);
        reviewDraftTemplates = clean(reviewDraftTemplates);
        safetyNotes = clean(safetyNotes);
        generatedAt = generatedAt == null ? LocalDateTime.now() : generatedAt;
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
