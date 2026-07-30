package com.hunt.otziv.workload_shadow.dto;

import java.time.LocalDateTime;

public record WorkloadTransferExecutionResponse(
        long id,
        long workflowId,
        String status,
        long managerId,
        String managerName,
        long sourceWorkerId,
        String sourceWorkerName,
        long targetWorkerId,
        String targetWorkerName,
        long companyId,
        String companyTitle,
        int orderCount,
        int reviewCount,
        int badTaskCount,
        int recoveryTaskCount,
        LocalDateTime startedAt,
        LocalDateTime appliedAt,
        LocalDateTime rollbackDeadlineAt,
        LocalDateTime rolledBackAt,
        String errorCode,
        String errorMessage
) {
}
