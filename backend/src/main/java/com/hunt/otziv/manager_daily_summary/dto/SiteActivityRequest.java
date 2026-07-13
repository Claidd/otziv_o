package com.hunt.otziv.manager_daily_summary.dto;

public record SiteActivityRequest(
        String activityType,
        String route,
        String sessionId
) {
}
