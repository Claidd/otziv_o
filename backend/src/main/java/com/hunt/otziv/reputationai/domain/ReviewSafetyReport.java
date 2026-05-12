package com.hunt.otziv.reputationai.domain;

import java.util.List;

public record ReviewSafetyReport(
        boolean safeToUseAsDraft,
        int riskScore,
        List<String> warnings,
        List<String> suggestions
) {
    public ReviewSafetyReport {
        riskScore = Math.max(0, Math.min(100, riskScore));
        warnings = safeList(warnings);
        suggestions = safeList(suggestions);
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
