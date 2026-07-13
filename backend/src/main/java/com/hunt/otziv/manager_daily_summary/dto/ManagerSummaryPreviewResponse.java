package com.hunt.otziv.manager_daily_summary.dto;

import java.time.LocalDate;
import java.util.List;

public record ManagerSummaryPreviewResponse(
        LocalDate date,
        String message,
        List<ManagerDailySummaryResponse> managers
) {
}
