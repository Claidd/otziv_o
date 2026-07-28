package com.hunt.otziv.workload_shadow.health;

import java.time.LocalDateTime;

public record WorkloadShadowHealthData(
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
        LocalDateTime oldestDueEventAt,
        LocalDateTime lastSuccessfulRunAt,
        LocalDateTime lastSnapshotAt
) {
}
