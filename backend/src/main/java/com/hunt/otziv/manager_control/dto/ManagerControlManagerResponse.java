package com.hunt.otziv.manager_control.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ManagerControlManagerResponse(
        Long managerId,
        Long userId,
        String username,
        String name,
        boolean active,
        Long dailyControlId,
        String dailyControlStatus,
        LocalDateTime startedAt,
        LocalDateTime closedAt,
        LocalDateTime morningStartedAt,
        LocalDateTime morningCompletedAt,
        LocalDateTime dayCheckedAt,
        LocalDateTime finalCheckedAt,
        int qualityScore,
        String qualityGrade,
        int riskScore,
        boolean fastClickRisk,
        boolean canCloseDay,
        long openItemCount,
        long handledItemCount,
        String status,
        long criticalCount,
        long warningCount,
        long workloadCount,
        long totalAttentionCount,
        long overdueOrderCount,
        long openRiskCount,
        long orderAttentionCount,
        long workerSectionCount,
        List<ManagerControlProblemResponse> problems,
        List<ManagerControlSectionResponse> workerSections,
        List<ManagerControlOverdueStatusResponse> overdueStatuses
) {
}
