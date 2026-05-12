package com.hunt.otziv.reputationai.domain;

import java.util.List;

public record CompanyAiProfile(
        String shortDescription,
        String category,
        List<String> products,
        List<String> advantages,
        List<String> positiveReviewTopics,
        List<String> negativeReviewTopics,
        List<String> factualWarnings
) {
    public CompanyAiProfile {
        shortDescription = shortDescription == null ? "" : shortDescription.trim();
        category = category == null ? "" : category.trim();
        products = safeList(products);
        advantages = safeList(advantages);
        positiveReviewTopics = safeList(positiveReviewTopics);
        negativeReviewTopics = safeList(negativeReviewTopics);
        factualWarnings = safeList(factualWarnings);
    }

    private static List<String> safeList(List<String> values) {
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
