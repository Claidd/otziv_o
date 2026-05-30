package com.hunt.otziv.gamification.dto;

public record GamificationMyBreakdownResponse(
        String eventType,
        long events,
        long points
) {
}
