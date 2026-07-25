package com.hunt.otziv.worker_activity.dto;

public record WorkerRiskAuditRequest(
        String decision,
        String comment
) {
}
