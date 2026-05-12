package com.hunt.otziv.reputationai.infrastructure.ai.openai;

import com.hunt.otziv.reputationai.config.ContentPackProfile;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;

import java.time.Duration;

public record OpenAiContentPackOptions(
        String profileKey,
        String model,
        Duration timeout,
        int maxOutputTokens,
        String reasoningEffort
) {
    public static OpenAiContentPackOptions configured(ReputationAiProperties.OpenAi openai) {
        return new OpenAiContentPackOptions(
                "configured",
                openai.getModel(),
                openai.getTimeout(),
                openai.getMaxOutputTokens(),
                ""
        );
    }

    public static OpenAiContentPackOptions fromProfile(
            ReputationAiProperties.OpenAi fallback,
            String profileKey
    ) {
        ContentPackProfile profile = ContentPackProfile.fromKey(profileKey);
        if (profile == null) {
            return configured(fallback);
        }

        return new OpenAiContentPackOptions(
                profile.key(),
                profile.model(),
                profile.timeout(),
                profile.maxOutputTokens(),
                profile.reasoningEffort()
        );
    }
}
