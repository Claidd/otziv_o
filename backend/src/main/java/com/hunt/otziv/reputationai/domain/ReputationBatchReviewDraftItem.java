package com.hunt.otziv.reputationai.domain;

import java.util.List;

public record ReputationBatchReviewDraftItem(
        Long reviewId,
        String idea,
        String draft,
        List<String> sourceFacts,
        List<String> safetyNotes
) {
    public ReputationBatchReviewDraftItem {
        idea = normalize(idea);
        draft = normalize(draft);
        sourceFacts = clean(sourceFacts);
        safetyNotes = clean(safetyNotes);
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
