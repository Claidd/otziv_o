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
        int questionGenerationRetryMaxTokens,
        ManagerReportReviewEvidenceSettings evidence
) {
    // Old callers keep their current rollout settings when the new block is omitted.
    public ManagerReportReviewSettingsRequest(
            boolean enabled, boolean managerGroupsEnabled, boolean restrictionEnabled, int maxQuestionCount,
            int minimumReadSeconds, int testMinimumReadSeconds, int reminderOneMinutes, int reminderThreeMinutes,
            int minimumAnswerScore, int maxAnswerCharacters, int maxPlanCharacters, int fastPasteSeconds,
            int fastPasteMinCharacters, int copyGramSize, int copySimilarityPercent, int aiTimeoutSeconds,
            int questionGenerationMaxTokens, int questionGenerationRetryMaxTokens
    ) {
        this(enabled, managerGroupsEnabled, restrictionEnabled, maxQuestionCount, minimumReadSeconds,
                testMinimumReadSeconds, reminderOneMinutes, reminderThreeMinutes, minimumAnswerScore,
                maxAnswerCharacters, maxPlanCharacters, fastPasteSeconds, fastPasteMinCharacters, copyGramSize,
                copySimilarityPercent, aiTimeoutSeconds, questionGenerationMaxTokens, questionGenerationRetryMaxTokens, null);
    }
}
