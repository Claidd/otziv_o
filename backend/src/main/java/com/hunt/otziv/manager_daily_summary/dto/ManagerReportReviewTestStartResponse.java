package com.hunt.otziv.manager_daily_summary.dto;

import java.time.LocalDate;

public record ManagerReportReviewTestStartResponse(
        Long reviewId,
        LocalDate date,
        Long sourceManagerId,
        String sourceManagerName,
        String recipient,
        int issueCount
) {
}
