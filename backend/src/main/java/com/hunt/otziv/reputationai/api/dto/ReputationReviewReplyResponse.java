package com.hunt.otziv.reputationai.api.dto;

import java.util.List;

public record ReputationReviewReplyResponse(
        List<String> replies,
        String warning
) {
}
