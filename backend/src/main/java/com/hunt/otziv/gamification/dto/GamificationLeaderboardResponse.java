package com.hunt.otziv.gamification.dto;

import java.time.LocalDate;
import java.util.List;

public record GamificationLeaderboardResponse(
        boolean enabled,
        LocalDate from,
        LocalDate to,
        int days,
        String actorRole,
        Integer ownRank,
        int totalActors,
        List<GamificationLeaderboardEntryResponse> entries
) {
}
