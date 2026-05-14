package com.hunt.otziv.reputationai.domain;

import java.time.LocalDateTime;
import java.util.List;

public record ReputationSingleReviewDraftResult(
        Long companyId,
        String companyName,
        Long deepReportJobId,
        Long contentPackJobId,
        String provider,
        String model,
        String idea,
        String style,
        String draft,
        List<String> sourceFacts,
        List<String> safetyNotes,
        ReviewSafetyReport safetyReport,
        LocalDateTime generatedAt
) {
    public ReputationSingleReviewDraftResult {
        companyName = normalize(companyName);
        provider = normalize(provider);
        model = normalize(model);
        idea = normalize(idea);
        style = normalize(style);
        draft = normalize(draft);
        sourceFacts = clean(sourceFacts);
        safetyNotes = clean(safetyNotes);
        generatedAt = generatedAt == null ? LocalDateTime.now() : generatedAt;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
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
