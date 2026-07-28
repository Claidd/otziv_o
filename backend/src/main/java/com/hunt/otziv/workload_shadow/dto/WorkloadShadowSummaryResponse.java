package com.hunt.otziv.workload_shadow.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record WorkloadShadowSummaryResponse(
        LocalDateTime updatedAt,
        LocalDate progressDate,
        String mode,
        boolean applyEnabled,
        boolean observationEnabled,
        int managerCount,
        int workerCount,
        int workersAt100,
        int atRiskWorkerCount,
        int transferCaseCount,
        int staffingSignalCount,
        long lateExcludedUnits,
        int missingManagerGroupCount,
        int missingWorkerGroupCount,
        WalkEstimateSummary walkEstimate,
        LastRun lastRun,
        List<ManagerSummary> managers
) {
    public record WalkEstimateSummary(
            int defaultMinutes,
            int minimumMinutes,
            int effectiveMinutes,
            long sampleCount,
            long averageSeconds,
            String source,
            LocalDateTime calculatedAt
    ) {
    }

    public record LastRun(
            Long id,
            String status,
            String triggerType,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            Long durationMs,
            String errorMessage
    ) {
    }

    public record ManagerSummary(
            Long managerId,
            String managerName,
            int workerCount,
            int workersAt100,
            BigDecimal progressPercent,
            int transferCaseCount,
            boolean staffingRequired,
            boolean groupConnected
    ) {
    }
}
