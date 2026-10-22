package com.hunt.otziv.manager_daily_summary.dto;

import java.util.List;

/** Owner/admin read model. Contains only the recorded answer and allowlisted assessment fields. */
public record ManagerReportReviewAnswerAttemptResponse(
        int attemptNumber, int questionIndex, String question, String answer, String recordedAt,
        String decision, String reason, boolean structuredAssessment,
        List<Criterion> criteria, List<Claim> claims
) {
    public record Criterion(int criterionIndex, String criterion, String status, int answerAttempt,
                            String answerQuote, List<String> evidenceRefs, String reason) { }
    public record Claim(String kind, String status, int answerAttempt,
                        String answerQuote, List<String> evidenceRefs, String reason) { }
}
