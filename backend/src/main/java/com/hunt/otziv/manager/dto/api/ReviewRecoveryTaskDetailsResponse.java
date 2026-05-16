package com.hunt.otziv.manager.dto.api;

public record ReviewRecoveryTaskDetailsResponse(
        Long id,
        Long batchId,
        Long sourceReviewId,
        String status,
        String statusCode,
        String recoveryText,
        String recoveryAnswer,
        String scheduledDate,
        String completedDate,
        String workerFio,
        Long botId,
        String botFio,
        String botLogin,
        String botPassword,
        ReviewRecoveryBatchDetailsResponse batch
) {
}
