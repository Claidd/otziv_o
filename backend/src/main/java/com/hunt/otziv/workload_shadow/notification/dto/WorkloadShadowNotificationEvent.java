package com.hunt.otziv.workload_shadow.notification.dto;

public record WorkloadShadowNotificationEvent(
        long id,
        String severity,
        String eventType,
        Long managerId,
        String title,
        String message,
        String targetGroupType,
        Long targetGroupChatId,
        int deliveryAttempts
) {
}
