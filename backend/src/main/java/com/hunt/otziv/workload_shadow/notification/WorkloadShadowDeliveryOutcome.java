package com.hunt.otziv.workload_shadow.notification;

import java.time.LocalDateTime;

public record WorkloadShadowDeliveryOutcome(
        long eventId,
        String deliveryStatus,
        int deliveryAttempts,
        LocalDateTime deliveredAt,
        LocalDateTime nextAttemptAt,
        String errorCode,
        String error
) {

    public static WorkloadShadowDeliveryOutcome sent(
            WorkloadShadowNotificationEvent event,
            LocalDateTime deliveredAt
    ) {
        return new WorkloadShadowDeliveryOutcome(
                event.id(),
                "SENT",
                0,
                deliveredAt,
                null,
                null,
                null
        );
    }

    public static WorkloadShadowDeliveryOutcome retry(
            WorkloadShadowNotificationEvent event,
            LocalDateTime nextAttemptAt,
            String errorCode,
            String error
    ) {
        return new WorkloadShadowDeliveryOutcome(
                event.id(),
                "RETRY",
                event.deliveryAttempts() + 1,
                null,
                nextAttemptAt,
                errorCode,
                error
        );
    }

    public static WorkloadShadowDeliveryOutcome dead(
            WorkloadShadowNotificationEvent event,
            int deliveryAttempts,
            String errorCode,
            String error
    ) {
        return new WorkloadShadowDeliveryOutcome(
                event.id(),
                "DEAD",
                deliveryAttempts,
                null,
                null,
                errorCode,
                error
        );
    }
}
