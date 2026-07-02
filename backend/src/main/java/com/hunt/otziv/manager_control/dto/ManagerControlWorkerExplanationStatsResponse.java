package com.hunt.otziv.manager_control.dto;

public record ManagerControlWorkerExplanationStatsResponse(
        Long workerUserId,
        String workerName,
        long requestCount,
        long unansweredCount,
        long overdueCount,
        double averageResponseMinutes
) {
}
