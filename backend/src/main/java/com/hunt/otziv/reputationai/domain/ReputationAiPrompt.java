package com.hunt.otziv.reputationai.domain;

import java.time.LocalDateTime;
import java.util.List;

public record ReputationAiPrompt(
        String key,
        String title,
        String description,
        String content,
        String defaultContent,
        boolean customized,
        LocalDateTime updatedAt,
        List<String> requiredPlaceholders,
        List<ReputationAiPromptPreset> presets
) {
}
