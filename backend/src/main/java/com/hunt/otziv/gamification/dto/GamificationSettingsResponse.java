package com.hunt.otziv.gamification.dto;

import java.time.LocalDateTime;

public record GamificationSettingsResponse(
        boolean enabled,
        boolean workerEnabled,
        boolean managerEnabled,
        boolean operatorEnabled,
        boolean marketologEnabled,
        boolean showInCabinet,
        boolean showInScore,
        boolean eventsEnabled,
        boolean shadowScoringEnabled,
        LocalDateTime updatedAt
) {
}
