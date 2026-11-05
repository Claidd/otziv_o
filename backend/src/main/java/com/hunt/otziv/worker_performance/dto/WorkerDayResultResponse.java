package com.hunt.otziv.worker_performance.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Final end-of-day result of one specialist. {@code eligibleCount}/{@code completedCount} are
 * the real numbers of the day; an owner excuse changes only {@code reached100} and the streak.
 */
public record WorkerDayResultResponse(
        Long workerId,
        LocalDate date,
        long eligibleCount,
        long completedCount,
        double progressPercent,
        boolean reached100,
        int streakDays,
        boolean excused,
        String excuseReason,
        String excusedBy,
        LocalDateTime excusedAt,
        boolean canExcuse,
        boolean canRevoke
) {
}
