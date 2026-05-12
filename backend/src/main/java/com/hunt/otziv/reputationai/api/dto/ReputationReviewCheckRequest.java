package com.hunt.otziv.reputationai.api.dto;

import java.util.List;

public record ReputationReviewCheckRequest(
        String text,
        List<String> allowedFacts
) {
}
