package com.hunt.otziv.gamification.dto;

public record GamificationLeaderboardEntryResponse(
        int rank,
        Long actorUserId,
        String actorName,
        String actorRole,
        long events,
        long points,
        int timelinessPercent,
        boolean currentUser
) {
}
