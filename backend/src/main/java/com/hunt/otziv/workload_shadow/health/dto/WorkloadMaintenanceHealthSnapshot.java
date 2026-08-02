package com.hunt.otziv.workload_shadow.health.dto;

import java.time.LocalDateTime;

public record WorkloadMaintenanceHealthSnapshot(
        boolean healthy,
        String repairStatus,
        String retentionStatus,
        LocalDateTime lastRepairStartedAt,
        LocalDateTime lastRepairSucceededAt,
        LocalDateTime lastRepairFailedAt,
        LocalDateTime lastRetentionStartedAt,
        LocalDateTime lastRetentionSucceededAt,
        LocalDateTime lastRetentionFailedAt,
        int repairConsecutiveFailures,
        int retentionConsecutiveFailures,
        String lastErrorCode,
        String lastErrorMessage
) {
}
