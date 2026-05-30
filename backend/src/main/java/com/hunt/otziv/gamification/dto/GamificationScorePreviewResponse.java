package com.hunt.otziv.gamification.dto;

import java.time.LocalDate;
import java.util.List;

public record GamificationScorePreviewResponse(
        LocalDate from,
        LocalDate to,
        int days,
        long totalPoints,
        List<GamificationScorePreviewActorResponse> topActors
) {
}
