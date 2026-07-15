package com.hunt.otziv.p_products.worker_access.dto;

import java.time.LocalDateTime;
import java.util.List;

public record WorkerNetworkViolationStatsResponse(
        boolean visible,
        long episodeCount,
        long attemptCount,
        int daysWithViolations,
        String severity,
        List<ViolationDetail> details
) {
    public static WorkerNetworkViolationStatsResponse empty() {
        return new WorkerNetworkViolationStatsResponse(true, 0, 0, 0, "NONE", List.of());
    }

    public record ViolationDetail(
            LocalDateTime firstSeenAt,
            LocalDateTime lastSeenAt,
            String reason,
            String scope,
            long attemptCount,
            String provider,
            boolean blocked
    ) {
    }
}
