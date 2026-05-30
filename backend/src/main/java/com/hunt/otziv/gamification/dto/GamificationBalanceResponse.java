package com.hunt.otziv.gamification.dto;

public record GamificationBalanceResponse(
        Long actorUserId,
        String actorName,
        String actorRole,
        long totalEvents,
        long totalPoints,
        long reviewPublishedEvents,
        long reviewPublishedPoints,
        long orderPaidEvents,
        long orderPaidPoints,
        long badReviewTaskDoneEvents,
        long badReviewTaskDonePoints,
        long reviewRecoveryTaskDoneEvents,
        long reviewRecoveryTaskDonePoints,
        long onTimeEvents,
        long delayedEvents,
        long lostPoints
) {
}
