package com.hunt.otziv.workload_shadow.health;

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
        LocalDateTime lastSnapshotAt
) {
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
                || (groupNotificationsEnabled && oldestDueAgeSeconds > 300);
    }
}
