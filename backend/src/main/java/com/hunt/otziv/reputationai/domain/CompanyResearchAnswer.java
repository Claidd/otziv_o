package com.hunt.otziv.reputationai.domain;

import java.util.List;

public record CompanyResearchAnswer(
        String key,
        String question,
        String answer,
        List<String> evidence,
        List<String> sourceUrls,
        int confidence,
        String status
) {
    public CompanyResearchAnswer {
        key = normalize(key);
        question = normalize(question);
        answer = normalize(answer);
        evidence = safeList(evidence);
        sourceUrls = safeList(sourceUrls);
        confidence = Math.max(0, Math.min(100, confidence));
        status = normalize(status);
        if (status.isBlank()) {
            status = confidence >= 70 ? "found" : confidence > 0 ? "partial" : "missing";
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
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
