package com.hunt.otziv.worker_activity.dto;

public record WorkerCredentialPreparationResponse(
        String scope,
        Long reviewId,
        Long botId,
        String loginCopiedAt,
        String passwordCopiedAt,
        String updatedAt
) {
}
