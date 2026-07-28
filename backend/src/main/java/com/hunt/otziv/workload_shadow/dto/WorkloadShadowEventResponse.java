package com.hunt.otziv.workload_shadow.dto;

import java.time.LocalDateTime;

public record WorkloadShadowEventResponse(
        Long id,
        String severity,
        String eventType,
        Long managerId,
        String managerName,
        Long workerId,
        String workerName,
        Long companyId,
        String companyTitle,
        String title,
        String message,
        String targetGroupType,
        boolean targetGroupConnected,
        String deliveryStatus,
        int deliveryAttempts,
        long occurrenceCount,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt,
        LocalDateTime deliveredAt,
        String lastErrorCode,
        String lastError,
        boolean active
) {
}
