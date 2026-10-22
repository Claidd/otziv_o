package com.hunt.otziv.manager_daily_summary.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ManagerReportReviewResponse(
        Long reviewId,
        LocalDate summaryDate,
        Long managerId,
        Long managerUserId,
        String managerName,
        boolean testMode,
        Long testOwnerUserId,
        String status,
        int currentQuestionIndex,
        int issueCount,
        int questionCount,
        int answerAttemptCount,
        int acceptedAnswerCount,
        int minimumReadSeconds,
        long readSeconds,
        long totalReviewSeconds,
        boolean quickReview,
        String questionsSource,
        boolean aiVerificationPaused,
        long aiUnavailableSeconds,
        int suspiciousAnswerCount,
        String answerQuality,
        String answerQualityReason,
        String actionPlan,
        boolean auditRequired,
        boolean autoCompleted,
        String disputeText,
        LocalDateTime deliveredAt,
        LocalDateTime startedAt,
        LocalDateTime readingConfirmedAt,
        LocalDateTime deadlineStartedAt,
        LocalDateTime completedAt,
        LocalDateTime disputedAt,
        LocalDateTime reminderOneSentAt,
        LocalDateTime reminderThreeSentAt,
        LocalDateTime restrictedAt,
        LocalDateTime restrictionReleasedAt,
        int openDisputeCount,
        List<ManagerReportReviewIssueResponse> issues,
        List<ManagerReportReviewEventResponse> events,
        List<ManagerReportReviewAnswerAttemptResponse> answerAttempts
) {
    public ManagerReportReviewResponse {
        answerAttempts = answerAttempts == null ? List.of() : List.copyOf(answerAttempts);
    }

    public ManagerReportReviewResponse(Long reviewId, LocalDate summaryDate, Long managerId, Long managerUserId,
            String managerName, boolean testMode, Long testOwnerUserId, String status, int currentQuestionIndex,
            int issueCount, int questionCount, int answerAttemptCount, int acceptedAnswerCount, int minimumReadSeconds,
            long readSeconds, long totalReviewSeconds, boolean quickReview, String questionsSource,
            boolean aiVerificationPaused, long aiUnavailableSeconds, int suspiciousAnswerCount,
            String answerQuality, String answerQualityReason, String actionPlan, boolean auditRequired,
            boolean autoCompleted, String disputeText, LocalDateTime deliveredAt, LocalDateTime startedAt,
            LocalDateTime readingConfirmedAt, LocalDateTime deadlineStartedAt, LocalDateTime completedAt,
            LocalDateTime disputedAt, LocalDateTime reminderOneSentAt, LocalDateTime reminderThreeSentAt,
            LocalDateTime restrictedAt, LocalDateTime restrictionReleasedAt, int openDisputeCount,
            List<ManagerReportReviewIssueResponse> issues, List<ManagerReportReviewEventResponse> events) {
        this(reviewId, summaryDate, managerId, managerUserId, managerName, testMode, testOwnerUserId, status,
                currentQuestionIndex, issueCount, questionCount, answerAttemptCount, acceptedAnswerCount,
                minimumReadSeconds, readSeconds, totalReviewSeconds, quickReview, questionsSource,
                aiVerificationPaused, aiUnavailableSeconds, suspiciousAnswerCount, answerQuality, answerQualityReason,
                actionPlan, auditRequired, autoCompleted, disputeText, deliveredAt, startedAt, readingConfirmedAt,
                deadlineStartedAt, completedAt, disputedAt, reminderOneSentAt, reminderThreeSentAt, restrictedAt,
                restrictionReleasedAt, openDisputeCount, issues, events, List.of());
    }
}
