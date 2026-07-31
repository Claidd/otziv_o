package com.hunt.otziv.notification_media.dto;

public record NotificationMediaRuleRequest(
        String eventCode,
        Boolean enabled,
        Integer imageProbabilityPercent,
        Integer cooldownMinutes
) {
}
