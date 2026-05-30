package com.hunt.otziv.archive.dto;

import java.time.LocalDate;

public record ArchiveRunResult(
        Long batchId,
        boolean dryRun,
        LocalDate cutoffDate,
        int retentionDays,
        int batchLimit,
        long eligibleOrders,
        ArchiveCandidateCounts selected,
        ArchiveCandidateCounts archived,
        ArchiveCandidateCounts deleted,
        long missingClosedAnalyticsMonths,
        String message
) {
}
