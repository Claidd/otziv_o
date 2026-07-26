package com.hunt.otziv.manager_daily_summary.dto;

import java.time.LocalDateTime;

public record ManagerReportReviewIssueResponse(
        Long issueId,
        int questionIndex,
        String title,
        String question,
        String status,
        Long disputeId,
        String disputeStatus,
        String disputeText,
        String ownerComment,
        LocalDateTime disputedAt,
        LocalDateTime resolvedAt
) {
}
