package com.hunt.otziv.gamification.dto;

import java.time.LocalDate;
import java.util.List;

public record GamificationMyProgressResponse(
        boolean enabled,
        LocalDate from,
        LocalDate to,
        int days,
        Long actorUserId,
        String actorName,
        String actorRole,
        long totalEvents,
        long totalPoints,
        long dailyGoal,
        long dailyProgress,
        int dailyGoalPercent,
        int level,
        long currentLevelPoints,
        long nextLevelPoints,
        long pointsToNextLevel,
        long onTimeEvents,
        long delayedEvents,
        long lostPoints,
        int timelinessPercent,
        int streakDays,
        List<GamificationMyMissionResponse> missions,
        List<GamificationMyBreakdownResponse> breakdown
) {
}
