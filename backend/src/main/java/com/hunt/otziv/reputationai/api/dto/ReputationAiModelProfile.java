package com.hunt.otziv.reputationai.api.dto;

public record ReputationAiModelProfile(
        String key,
        String label,
        String model,
        String description,
        int maxToolCalls,
        int maxOutputTokens,
        String reasoningEffort,
        String searchContextSize
) {
}
