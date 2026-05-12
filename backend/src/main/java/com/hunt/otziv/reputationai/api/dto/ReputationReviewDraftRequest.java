package com.hunt.otziv.reputationai.api.dto;

import java.util.List;

public record ReputationReviewDraftRequest(
        String productOrService,
        List<String> realExperiencePoints,
        String tone,
        String length
) {
}
