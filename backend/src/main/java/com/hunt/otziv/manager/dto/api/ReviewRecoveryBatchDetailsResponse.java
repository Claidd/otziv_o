package com.hunt.otziv.manager.dto.api;

public record ReviewRecoveryBatchDetailsResponse(
        Long id,
        String status,
        String statusCode,
        String completedAt,
        String clientNotifiedAt
) {
}
