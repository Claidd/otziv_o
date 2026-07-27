package com.hunt.otziv.manager_daily_summary.dto;

import java.util.List;

public record ManagerReportReviewSettingsResponse(
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
        List<ManagerReportReviewManagerSettingResponse> managers
) {
}
