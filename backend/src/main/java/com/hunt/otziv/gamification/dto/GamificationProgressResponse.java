package com.hunt.otziv.gamification.dto;

import java.time.LocalDate;
import java.util.List;

public record GamificationProgressResponse(
        LocalDate from,
        LocalDate to,
        int days,
        long totalEvents,
        List<GamificationEventTypeProgressResponse> eventTypes,
        List<GamificationActorProgressResponse> topActors
) {
}
