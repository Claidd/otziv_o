package com.hunt.otziv.gamification.dto;

public record GamificationScorePreviewActorResponse(
        Long actorUserId,
        String actorName,
        String actorRole,
        long totalEvents,
        long totalPoints
) {
}
