package com.hunt.otziv.manager_daily_summary.dto;

public record ManagerReportReviewManagerSettingResponse(
        Long managerId,
        String managerName,
        boolean userActive,
        boolean auditEnabled,
        boolean auditGroupConnected
) {
}
