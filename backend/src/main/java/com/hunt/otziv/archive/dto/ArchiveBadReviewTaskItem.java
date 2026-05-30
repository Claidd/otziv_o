package com.hunt.otziv.archive.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ArchiveBadReviewTaskItem(
        Long id,
        Long sourceReviewId,
        String status,
        Integer originalRating,
        Integer targetRating,
        BigDecimal price,
        LocalDate scheduledDate,
        LocalDate completedDate,
        String workerFio,
        String botFio,
        String comment
) {
}
