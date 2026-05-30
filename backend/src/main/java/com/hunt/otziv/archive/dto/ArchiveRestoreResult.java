package com.hunt.otziv.archive.dto;

import java.time.LocalDateTime;

public record ArchiveRestoreResult(
        Long batchId,
        Long orderId,
        LocalDateTime restoredAt,
        String restoredBy,
        String targetStatus,
        ArchiveCandidateCounts selected,
        ArchiveCandidateCounts restored,
        String message
) {
}
