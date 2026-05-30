package com.hunt.otziv.gamification.dto;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.math.BigDecimal;

public record GamificationEventResponse(
        Long id,
        String eventType,
        Long actorUserId,
        String actorRole,
        String actorName,
        Long orderId,
        Long reviewId,
        Long badReviewTaskId,
        Long recoveryTaskId,
        Long workerId,
        Long managerId,
        Long companyId,
        String companyTitle,
        String source,
        String payload,
        LocalDate plannedDate,
        LocalDate actualDate,
        Integer delayDays,
        String timelinessBucket,
        BigDecimal timelinessMultiplier,
        LocalDateTime createdAt
) {
}
