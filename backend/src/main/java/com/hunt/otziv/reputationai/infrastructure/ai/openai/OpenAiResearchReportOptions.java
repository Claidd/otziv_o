package com.hunt.otziv.reputationai.infrastructure.ai.openai;

import com.hunt.otziv.reputationai.config.DeepResearchProfile;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;

import java.time.Duration;

public record OpenAiResearchReportOptions(
        String profileKey,
        String model,
        Duration timeout,
        int maxToolCalls,
        int maxOutputTokens,
        String reasoningEffort,
        String searchContextSize
) {
    public static OpenAiResearchReportOptions configured(ReputationAiProperties.OpenAi.ResearchReport report) {
        return new OpenAiResearchReportOptions(
                "configured",
                report.getModel(),
                report.getTimeout(),
                report.getMaxToolCalls(),
                report.getMaxOutputTokens(),
                report.getReasoningEffort(),
                report.getSearchContextSize()
        );
    }

    public static OpenAiResearchReportOptions fromProfile(
            ReputationAiProperties.OpenAi.ResearchReport fallback,
            String profileKey
    ) {
        DeepResearchProfile profile = DeepResearchProfile.fromKey(profileKey);
        if (profile == null) {
            return configured(fallback);
        }

        return new OpenAiResearchReportOptions(
                profile.key(),
                profile.model(),
                profile.timeout(),
                profile.maxToolCalls(),
                profile.maxOutputTokens(),
                profile.reasoningEffort(),
                profile.searchContextSize()
        );
    }
}
