package com.hunt.otziv.manager.dto.api;

import java.math.BigDecimal;

public record BadReviewTaskDetailsResponse(
        Long id,
        Long sourceReviewId,
        String status,
        String statusCode,
        Integer originalRating,
        Integer targetRating,
        BigDecimal price,
        String scheduledDate,
        String completedDate,
        String workerFio,
        Long botId,
        String botFio,
        String comment
) {
}
