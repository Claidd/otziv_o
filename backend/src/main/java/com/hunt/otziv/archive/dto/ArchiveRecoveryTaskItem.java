package com.hunt.otziv.archive.dto;

import java.time.LocalDate;

public record ArchiveRecoveryTaskItem(
        Long id, Long reviewId, Long workerId, String workerName, LocalDate scheduledDate, String status
) {
}
