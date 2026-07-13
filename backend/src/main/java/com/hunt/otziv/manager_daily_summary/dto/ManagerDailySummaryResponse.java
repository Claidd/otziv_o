package com.hunt.otziv.manager_daily_summary.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ManagerDailySummaryResponse(
        LocalDate date,
        Long managerId,
        Long managerUserId,
        String managerName,
        int score,
        String grade,
        long taskTotal,
        long taskCompleted,
        long taskOpen,
        BigDecimal taskProgressPercent,
        long overdueCount,
        long riskCount,
        long unansweredCount,
        long firstReplyAverageSeconds,
        long firstReplyMedianSeconds,
        long allReplyAverageSeconds,
        long allReplyMedianSeconds,
        long allReplyP90Seconds,
        long replyCount,
        long repliesInSla,
        long problemCount,
        long problemResolvedCount,
        long problemResolutionAverageSeconds,
        long siteActiveSeconds,
        long messengerActiveSeconds,
        long confirmedActiveSeconds,
        String aggregationStatus
) {
}
