package com.hunt.otziv.archive;

import java.time.LocalDate;

public record ArchiveDryRunResult(
        Long batchId,
        boolean dryRun,
        LocalDate cutoffDate,
        int retentionDays,
        int batchLimit,
        long eligibleOrders,
        ArchiveCandidateCounts selected,
        long missingClosedAnalyticsMonths,
        String message
) {
}
