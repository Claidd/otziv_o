package com.hunt.otziv.manager_control.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ManagerControlSummaryResponse(
        LocalDate date,
        LocalDateTime generatedAt,
        boolean testMode,
        boolean managerVisible,
        long managersTotal,
        long greenCount,
        long yellowCount,
        long redCount,
        long criticalTotal,
        long warningTotal,
        long workloadTotal,
        long attentionTotal,
        List<ManagerControlManagerResponse> managers
) {
}
