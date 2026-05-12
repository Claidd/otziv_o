package com.hunt.otziv.reputationai.domain;

import java.time.LocalDateTime;

public record DeepCompanyResearchJobStatus(
        Long jobId,
        Long companyId,
        String companyName,
        String status,
        String provider,
        String model,
        String responseId,
        String errorMessage,
        DeepCompanyResearchReport report,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
    public DeepCompanyResearchJobStatus {
        companyName = normalize(companyName);
        status = normalize(status);
        provider = normalize(provider);
        model = normalize(model);
        responseId = normalize(responseId);
        errorMessage = normalize(errorMessage);
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
