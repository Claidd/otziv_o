package com.hunt.otziv.workload_shadow.repository.projection;

import java.time.LocalDateTime;

public interface WorkloadShadowHealthProjection {

    Long getDueEvents();

    Long getProcessingEvents();

    Long getStaleProcessingEvents();

    Long getDeadEvents();

    Long getMissingGroupBindings();

    Long getRunningRuns();

    Long getStaleRunningRuns();

    Long getGraphWarningCases();

    Long getGraphErrorCases();

    Long getExpiredRecalculationLocks();

    LocalDateTime getOldestDueEventAt();

    LocalDateTime getLastSuccessfulRunAt();

    LocalDateTime getLastSnapshotAt();
}
