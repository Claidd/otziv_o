package com.hunt.otziv.workload_shadow.dto;

import java.util.List;

public record WorkloadLiveSettingsRequest(
        String historyStartDate,
        int minFinalizedDays,
        int stableHours,
        int minCandidatesPerManager,
        List<Long> canaryManagerIds,
        int offerTimeoutMinutes,
        String offerStartTime,
        String offerEndTime,
        int maxTransfersPerManagerDay,
        int maxTransfersGlobalDay,
        int rollbackWindowMinutes,
        int firstLiveOwnerConfirmations,
        boolean emergencyFallbackEnabled,
        Long revision,
        Integer retentionDays
) {
    public WorkloadLiveSettingsRequest(
            String historyStartDate,
            int minFinalizedDays,
            int stableHours,
            int minCandidatesPerManager,
            List<Long> canaryManagerIds,
            int offerTimeoutMinutes,
            String offerStartTime,
            String offerEndTime,
            int maxTransfersPerManagerDay,
            int maxTransfersGlobalDay,
            int rollbackWindowMinutes,
            int firstLiveOwnerConfirmations,
            boolean emergencyFallbackEnabled,
            Long revision
    ) {
        this(
                historyStartDate,
                minFinalizedDays,
                stableHours,
                minCandidatesPerManager,
                canaryManagerIds,
                offerTimeoutMinutes,
                offerStartTime,
                offerEndTime,
                maxTransfersPerManagerDay,
                maxTransfersGlobalDay,
                rollbackWindowMinutes,
                firstLiveOwnerConfirmations,
                emergencyFallbackEnabled,
                revision,
                null
        );
    }
}
