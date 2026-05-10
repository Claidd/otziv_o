package com.hunt.otziv.archive;

import java.time.LocalDateTime;

public record ArchiveBatchSummary(
        Long batchId,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        boolean dryRun,
        String status,
        String archiveReason,
        int retentionDays,
        long ordersSelected,
        long ordersArchived,
        long orderDetailsArchived,
        long reviewsArchived,
        long badReviewTasksArchived,
        long nextOrderRequestsArchived,
        long zpArchived,
        long paymentCheckArchived,
        String message
) {
}
