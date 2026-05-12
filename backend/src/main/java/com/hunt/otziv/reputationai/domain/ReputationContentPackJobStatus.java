package com.hunt.otziv.reputationai.domain;

import java.time.LocalDateTime;

public record ReputationContentPackJobStatus(
        Long jobId,
        Long companyId,
        String companyName,
        String status,
        String provider,
        String model,
        String errorMessage,
        ReputationContentPack pack,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
    public ReputationContentPackJobStatus {
        companyName = normalize(companyName);
        status = normalize(status);
        provider = normalize(provider);
        model = normalize(model);
        errorMessage = normalize(errorMessage);
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
