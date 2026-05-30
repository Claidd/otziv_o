package com.hunt.otziv.gamification.dto;

public record GamificationMyMissionResponse(
        String code,
        String title,
        String description,
        long progress,
        long target,
        int percent,
        boolean completed
) {
}
