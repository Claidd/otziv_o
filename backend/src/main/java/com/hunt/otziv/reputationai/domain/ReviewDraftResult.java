package com.hunt.otziv.reputationai.domain;

import java.util.List;

public record ReviewDraftResult(
        String draft,
        String warning,
        List<String> usedExperiencePoints,
        ReviewSafetyReport safetyReport
) {
    public ReviewDraftResult {
        draft = draft == null ? "" : draft.trim();
        warning = warning == null ? "" : warning.trim();
        usedExperiencePoints = usedExperiencePoints == null ? List.of() : List.copyOf(usedExperiencePoints);
    }
}
