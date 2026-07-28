package com.hunt.otziv.workload_shadow.notification;

public record WorkloadShadowClaimedNotification(
        WorkloadShadowNotificationEvent event,
        Long managerAuditGroupChatId
) {
}
