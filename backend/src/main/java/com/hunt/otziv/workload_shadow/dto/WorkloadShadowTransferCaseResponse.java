package com.hunt.otziv.workload_shadow.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record WorkloadShadowTransferCaseResponse(
        Long id,
        Long managerId,
        String managerName,
        Long sourceWorkerId,
        String sourceWorkerName,
        Long companyId,
        String companyTitle,
        int failureNumber,
        int transferPercent,
        int selectionRank,
        long problemUnits,
        long estimatedMinutes,
        Graph graph,
        int graphWarningCount,
        int graphErrorCount,
        String graphWarningCodes,
        String graphErrorCodes,
        boolean staffingRequired,
        Long fallbackWorkerId,
        String fallbackWorkerName,
        Long fallbackReviewId,
        String status,
        LocalDateTime firstDetectedAt,
        LocalDateTime lastSeenAt,
        List<Candidate> candidates
) {
    public record Graph(
            long activeOrders,
            long newUnits,
            long correction,
            long nagul,
            long publish,
            long recovery,
            long bad
    ) {
    }

    public record Candidate(
            Long workerId,
            String workerName,
            int sequenceNumber,
            BigDecimal rating,
            int hundredPercentDays,
            int failureDays,
            long currentEstimatedMinutes,
            boolean workerGroupConnected,
            String simulatedOfferStatus
    ) {
    }
}
