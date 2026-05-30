package com.hunt.otziv.gamification.dto;

public record GamificationSettingsRequest(
        Boolean enabled,
        Boolean workerEnabled,
        Boolean managerEnabled,
        Boolean operatorEnabled,
        Boolean marketologEnabled,
        Boolean showInCabinet,
        Boolean showInScore,
        Boolean eventsEnabled,
        Boolean shadowScoringEnabled
) {
}
