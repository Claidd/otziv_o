package com.hunt.otziv.workload_shadow.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record WorkloadEmergencyAssignmentResponse(
        long id,
        String mode,
        String status,
        long sourceManagerId,
        String sourceManagerName,
        long sourceWorkerId,
        String sourceWorkerName,
        long targetManagerId,
        String targetManagerName,
        long targetWorkerId,
        String targetWorkerName,
        long companyId,
        String companyTitle,
        long reviewId,
        String reason,
        String targetNotificationStatus,
        String auditNotificationStatus,
        int notificationAttempts,
        LocalDate decisionDate,
        LocalDateTime appliedAt,
        LocalDateTime rollbackDeadlineAt,
        LocalDateTime rolledBackAt,
        String lastError
) {
}
