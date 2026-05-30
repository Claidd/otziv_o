package com.hunt.otziv.archive.dto;

import java.time.LocalDate;
import java.util.List;

public record ArchiveCandidatesPreview(
        LocalDate cutoffDate,
        int retentionDays,
        int batchLimit,
        long eligibleOrders,
        ArchiveCandidateCounts selected,
        long missingClosedAnalyticsMonths,
        List<ArchiveOrderCandidateItem> items
) {
}
