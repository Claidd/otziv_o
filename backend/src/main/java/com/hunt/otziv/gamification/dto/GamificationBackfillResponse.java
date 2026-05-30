package com.hunt.otziv.gamification.dto;

import java.time.LocalDate;

public record GamificationBackfillResponse(
        LocalDate from,
        LocalDate to,
        int days,
        long reviewedCandidates,
        long eventsCreated,
        long reviewPublishedReviewed,
        long reviewPublishedCreated,
        long orderPaidReviewed,
        long orderPaidCreated,
        long badReviewTaskDoneReviewed,
        long badReviewTaskDoneCreated,
        long reviewRecoveryTaskDoneReviewed,
        long reviewRecoveryTaskDoneCreated,
        GamificationScoreLedgerRebuildResponse ledgerRebuild
) {
}
