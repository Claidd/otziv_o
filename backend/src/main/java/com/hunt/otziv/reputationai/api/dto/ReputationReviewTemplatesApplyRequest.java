package com.hunt.otziv.reputationai.api.dto;

import java.util.List;

public record ReputationReviewTemplatesApplyRequest(
        Long contentPackJobId,
        List<String> honestReviewTopics,
        List<String> reviewDraftTemplates
) {
    public ReputationReviewTemplatesApplyRequest {
        honestReviewTopics = clean(honestReviewTopics);
        reviewDraftTemplates = clean(reviewDraftTemplates);
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
