package com.hunt.otziv.worker_performance.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record DailyWorkProgressResponse(
        boolean visible,
        String roleType,
        LocalDate date,
        long completed,
        long active,
        long total,
        int percent,
        boolean checked,
        LocalDateTime firstCompletedAt,
        LocalDateTime lastCompletedAt,
        long averageCloseSeconds,
        long medianCloseSeconds,
        long p90CloseSeconds,
        long loadScore,
        int efficiencyScore
) {
    public static DailyWorkProgressResponse hidden(String roleType, LocalDate date) {
        return new DailyWorkProgressResponse(
                false,
                roleType,
                date,
                0,
                0,
                0,
                0,
                false,
                null,
                null,
                0,
                0,
                0,
                0,
                0
        );
    }
}
