package com.hunt.otziv.reputationai.api.dto;

import java.util.List;

public record ReputationAiStatus(
        String aiProvider,
        boolean aiAvailable,
        String searchProvider,
        boolean searchAvailable,
        boolean yandexGptConfigured,
        boolean yandexSearchConfigured,
        boolean openAiConfigured,
        boolean openAiProxyEnabled,
        String yandexModel,
        String openAiModel,
        String openAiResearchReportModel,
        String openAiContentPackModel,
        List<ReputationAiModelProfile> openAiResearchReportProfiles,
        List<ReputationAiModelProfile> openAiContentPackProfiles,
        List<String> warnings
) {
}
