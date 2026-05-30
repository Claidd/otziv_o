package com.hunt.otziv.gamification.dto;

import java.time.LocalDateTime;

public record GamificationRuleResponse(
        String eventType,
        boolean enabled,
        int points,
        LocalDateTime updatedAt
) {
}
