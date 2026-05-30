package com.hunt.otziv.gamification.dto;

public record GamificationActorProgressResponse(
        Long actorUserId,
        String actorName,
        String actorRole,
        long events
) {
}
