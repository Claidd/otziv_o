package com.hunt.otziv.manager_control.dto;

import java.time.LocalDateTime;

public record ManagerControlConcreteItemResponse(
        Long controlEntityId,
        String type,
        Long entityId,
        String title,
        String subtitle,
        String status,
        Long ageDays,
        String reason,
        String targetUrl,
        String orderDetailsId,
        String chatUrl,
        LocalDateTime followUpAt,
        LocalDateTime lastManualTouchAt,
        String itemStatus,
        String actionType,
        String comment,
        LocalDateTime updatedAt,
        LocalDateTime resolvedAt,
        LocalDateTime workerNotificationAttemptedAt,
        LocalDateTime workerNotificationSentAt,
        LocalDateTime workerNotificationAcceptedAt,
        Long workerNotificationAcceptedByUserId,
        String workerNotificationFailureReason,
        String contactText
) {
    public ManagerControlConcreteItemResponse(
            Long controlEntityId,
            String type,
            Long entityId,
            String title,
            String subtitle,
            String status,
            Long ageDays,
            String reason,
            String targetUrl,
            String orderDetailsId,
            String chatUrl,
            LocalDateTime followUpAt,
            LocalDateTime lastManualTouchAt,
            String itemStatus,
            String actionType,
            String comment,
            LocalDateTime updatedAt,
            LocalDateTime resolvedAt,
            String contactText
    ) {
        this(
                controlEntityId,
                type,
                entityId,
                title,
                subtitle,
                status,
                ageDays,
                reason,
                targetUrl,
                orderDetailsId,
                chatUrl,
                followUpAt,
                lastManualTouchAt,
                itemStatus,
                actionType,
                comment,
                updatedAt,
                resolvedAt,
                null,
                null,
                null,
                null,
                null,
                contactText
        );
    }
}
