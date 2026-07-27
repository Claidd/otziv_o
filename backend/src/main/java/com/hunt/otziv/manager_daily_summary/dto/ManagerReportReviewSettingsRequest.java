package com.hunt.otziv.manager_daily_summary.dto;

public record ManagerReportReviewSettingsRequest(
        boolean enabled,
        boolean managerGroupsEnabled,
        boolean restrictionEnabled,
        int maxQuestionCount,
        int minimumReadSeconds,
        int testMinimumReadSeconds,
        int reminderOneMinutes,
        int reminderThreeMinutes,
        int minimumAnswerScore,
        int maxAnswerCharacters,
        int maxPlanCharacters,
        int fastPasteSeconds,
        int fastPasteMinCharacters,
        int copyGramSize,
        int copySimilarityPercent,
        int aiTimeoutSeconds,
        int questionGenerationMaxTokens,
        int questionGenerationRetryMaxTokens
) {
}
