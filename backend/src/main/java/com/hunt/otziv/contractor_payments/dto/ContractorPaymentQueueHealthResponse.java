package com.hunt.otziv.contractor_payments.dto;

import java.time.LocalDateTime;

public record ContractorPaymentQueueHealthResponse(
        QueueHealth allocationReconciliation,
        QueueHealth rewardRepair,
        QueueHealth shadowBackfill,
        QueueHealth completionRewardRepair,
        LocalDateTime observedAt
) {
    public record QueueHealth(
            long activeClaims,
            long expiredClaims,
            long retrying,
            long dueRetries,
            LocalDateTime oldestRetryAt,
            LocalDateTime oldestDueRetryAt,
            String lastErrorCode
    ) {
    }
}
