package com.hunt.otziv.workload_shadow.dto;

import java.time.LocalDateTime;

public record WorkloadShadowRunResponse(
        Long runId,
        String status,
        String triggerType,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        int managerCount,
        int workerCount,
        int transferCaseCount,
        int eventCount,
        String message
) {
}
