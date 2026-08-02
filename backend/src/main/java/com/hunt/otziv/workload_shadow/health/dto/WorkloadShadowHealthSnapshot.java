package com.hunt.otziv.workload_shadow.health.dto;

import java.time.LocalDateTime;

public record WorkloadShadowHealthSnapshot(
        String status,
        LocalDateTime checkedAt,
        boolean groupNotificationsEnabled,
        long dueEvents,
        long processingEvents,
        long staleProcessingEvents,
        long deadEvents,
        long missingGroupBindings,
        long runningRuns,
        long staleRunningRuns,
        long graphWarningCases,
        long graphErrorCases,
        long expiredRecalculationLocks,
        long snapshotAgeSeconds,
        long oldestDueAgeSeconds,
        LocalDateTime oldestDueEventAt,
        LocalDateTime lastSuccessfulRunAt,
        LocalDateTime lastSnapshotAt,
        WorkloadMaintenanceHealthSnapshot maintenance
) {
    public WorkloadShadowHealthSnapshot(
            String status,
            LocalDateTime checkedAt,
            boolean groupNotificationsEnabled,
            long dueEvents,
            long processingEvents,
            long staleProcessingEvents,
            long deadEvents,
            long missingGroupBindings,
            long runningRuns,
            long staleRunningRuns,
            long graphWarningCases,
            long graphErrorCases,
            long expiredRecalculationLocks,
            long snapshotAgeSeconds,
            long oldestDueAgeSeconds,
            LocalDateTime oldestDueEventAt,
            LocalDateTime lastSuccessfulRunAt,
            LocalDateTime lastSnapshotAt
    ) {
        this(
                status,
                checkedAt,
                groupNotificationsEnabled,
                dueEvents,
                processingEvents,
                staleProcessingEvents,
                deadEvents,
                missingGroupBindings,
                runningRuns,
                staleRunningRuns,
                graphWarningCases,
                graphErrorCases,
                expiredRecalculationLocks,
                snapshotAgeSeconds,
                oldestDueAgeSeconds,
                oldestDueEventAt,
                lastSuccessfulRunAt,
                lastSnapshotAt,
                new WorkloadMaintenanceHealthSnapshot(
                        true,
                        "UP",
                        "UP",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        0,
                        null,
                        null
                )
        );
    }

    public boolean maintenanceHealthy() {
        return maintenance != null && maintenance.healthy();
    }

    public boolean stale() {
        return staleProcessingEvents > 0
                || staleRunningRuns > 0
                || expiredRecalculationLocks > 0;
    }

    public boolean degraded() {
        return stale()
                || deadEvents > 0
                || missingGroupBindings > 0
                || graphWarningCases > 0
                || graphErrorCases > 0
                || !maintenanceHealthy()
                || (groupNotificationsEnabled && oldestDueAgeSeconds > 300);
    }
}
