package com.hunt.otziv.manager_control.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ManagerControlManagerDetailResponse(
        Long managerId,
        Long userId,
        String username,
        String name,
        Long dailyControlId,
        LocalDate controlDate,
        String dailyControlStatus,
        LocalDateTime startedAt,
        LocalDateTime closedAt,
        LocalDateTime lastActivityAt,
        LocalDateTime morningStartedAt,
        LocalDateTime morningCompletedAt,
        LocalDateTime dayCheckedAt,
        LocalDateTime finalCheckedAt,
        int qualityScore,
        String qualityGrade,
        int riskScore,
        boolean fastClickRisk,
        boolean canCloseDay,
        List<String> closeBlockers,
        long openItemCount,
        long handledItemCount,
        List<ManagerControlWorkerExplanationStatsResponse> workerExplanationStats,
        List<ManagerControlItemDetailResponse> items,
        List<ManagerControlEventResponse> events
) {
}
