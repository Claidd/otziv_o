package com.hunt.otziv.workload_shadow.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record WorkloadTransferWorkflowResponse(
        long id,
        String key,
        String mode,
        String status,
        long managerId,
        String managerName,
        long sourceWorkerId,
        String sourceWorkerName,
        Long targetWorkerId,
        String targetWorkerName,
        long companyId,
        String companyTitle,
        int failureNumber,
        int transferPercent,
        long problemUnits,
        long estimatedMinutes,
        long activeOrderCount,
        long newUnitCount,
        long correctionCount,
        long nagulCount,
        long publishCount,
        long recoveryCount,
        long badCount,
        boolean ownerConfirmationRequired,
        LocalDateTime ownerConfirmedAt,
        String lastErrorCode,
        String lastErrorMessage,
        LocalDate decisionDate,
        LocalDateTime lastTransitionAt,
        LocalDateTime createdAt,
        LocalDateTime currentOfferExpiresAt,
        long candidateCount,
        long declinedCandidateCount,
        long unavailableCandidateCount
) {
}
