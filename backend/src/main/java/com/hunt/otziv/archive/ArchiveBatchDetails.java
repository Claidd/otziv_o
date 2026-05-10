package com.hunt.otziv.archive;

import java.util.List;

public record ArchiveBatchDetails(
        ArchiveBatchSummary summary,
        ArchiveCandidateCounts totals,
        List<ArchiveOrderCandidateItem> orders
) {
}
