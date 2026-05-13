package com.hunt.otziv.reputationai.domain;

import java.time.LocalDateTime;

public record ReputationAiPromptVersion(
        Long id,
        String key,
        String action,
        String actor,
        String previousContent,
        String content,
        LocalDateTime createdAt
) {
}
