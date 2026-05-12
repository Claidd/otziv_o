package com.hunt.otziv.reputationai.api.dto;

import com.hunt.otziv.reputationai.domain.ReviewSafetyReport;

public record ReputationReviewRewriteResponse(
        String rewrittenText,
        ReviewSafetyReport safetyReport
) {
}
