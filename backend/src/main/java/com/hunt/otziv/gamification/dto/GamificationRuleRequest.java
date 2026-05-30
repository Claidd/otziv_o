package com.hunt.otziv.gamification.dto;

public record GamificationRuleRequest(
        String eventType,
        Boolean enabled,
        Integer points
) {
}
