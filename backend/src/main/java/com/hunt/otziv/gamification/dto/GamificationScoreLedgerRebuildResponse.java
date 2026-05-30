package com.hunt.otziv.gamification.dto;

import java.time.LocalDate;

public record GamificationScoreLedgerRebuildResponse(
        LocalDate from,
        LocalDate to,
        int days,
        boolean shadowScoringEnabled,
        long eventsReviewed,
        long entriesDeleted,
        long entriesCreated,
        long totalPoints
) {
}
