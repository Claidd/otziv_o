package com.hunt.otziv.manager_daily_summary.dto;

import java.time.LocalDateTime;

public record ManagerReportReviewEventResponse(
        Long eventId,
        String eventType,
        Long actorUserId,
        String actorRole,
        String source,
        String payload,
        LocalDateTime createdAt
) {
}
