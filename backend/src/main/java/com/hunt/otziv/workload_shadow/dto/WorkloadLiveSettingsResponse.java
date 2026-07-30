package com.hunt.otziv.workload_shadow.dto;

import java.util.List;

public record WorkloadLiveSettingsResponse(
        String mode,
        boolean applyEnabled,
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
        long revision,
        int retentionDays
) {
    public WorkloadLiveSettingsResponse(
            String mode,
            boolean applyEnabled,
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
            long revision
    ) {
        this(
                mode,
                applyEnabled,
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
                400
        );
    }
}
