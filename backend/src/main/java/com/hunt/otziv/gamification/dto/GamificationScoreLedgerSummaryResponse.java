package com.hunt.otziv.gamification.dto;

import java.time.LocalDate;
import java.util.List;

public record GamificationScoreLedgerSummaryResponse(
        LocalDate from,
        LocalDate to,
        int days,
        long totalEvents,
        long totalPoints,
        long previewPoints,
        long pointsDelta,
        List<GamificationScorePreviewActorResponse> topActors
) {
}
