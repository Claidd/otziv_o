package com.hunt.otziv.workload_shadow.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record WorkloadShadowWorkerResponse(
        Long workerId,
        Long workerUserId,
        Long managerId,
        String managerName,
        String workerName,
        LocalDate progressDate,
        LocalDateTime snapshotAt,
        BigDecimal progressPercent,
        long completedUnits,
        long activeUnits,
        long eligibleUnits,
        long lateExcludedUnits,
        long feasibleUnits,
        long estimatedRemainingMinutes,
        long plannedUnits,
        long incomingUnits,
        long urgentUnits,
        long externalBlockedUnits,
        long clientDeferredUnits,
        long managerDeferredUnits,
        long blockedUnits,
        long newUnits,
        long correctionUnits,
        long nagulUnits,
        long publishUnits,
        long recoveryUnits,
        long badUnits,
        BigDecimal rating,
        int hundredPercentDays,
        int failureDays,
        int evaluatedDays,
        int freezeCredits,
        int transferStage,
        boolean lastDayReached100,
        boolean acceptsCompanyTransfers,
        boolean recipientEligible,
        boolean workerGroupConnected,
        String diagnosticStatus,
        LocalDateTime lastAvailableAt
) {
}
