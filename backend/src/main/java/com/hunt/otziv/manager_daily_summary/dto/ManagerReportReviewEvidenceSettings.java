package com.hunt.otziv.manager_daily_summary.dto;

import java.util.List;

/** Independent rollout and resource limits for diagnosis, questions and answer verification. */
public record ManagerReportReviewEvidenceSettings(
        String diagnosisMode,
        List<Long> diagnosisPilotManagerIds,
        int diagnosisMaxRequestsPerHour,
        int diagnosisMaxOutputTokens,
        int diagnosisTimeoutSeconds,
        boolean diagnosisThinkingEnabled,
        String answerMode,
        List<Long> answerPilotManagerIds,
        int answerMaxTokens,
        int answerTimeoutSeconds,
        boolean answerThinkingEnabled,
        int questionTimeoutSeconds,
        boolean questionThinkingEnabled,
        int evidenceMaxAgeMinutes
) { }
