package com.hunt.otziv.manager_daily_summary.dto;

import java.time.LocalDate;

public record ManagerSummaryTelegramSendResponse(
        LocalDate date,
        int managerCount,
        int messageCount,
        String recipient
) {
}
