package com.hunt.otziv.gamification.dto;

public record GamificationEventTypeProgressResponse(
        String eventType,
        long events
) {
}
