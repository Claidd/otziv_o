package com.hunt.otziv.gamification.dto;

import java.time.LocalDateTime;

public record GamificationRewardClaimResponse(
        Long id,
        Long rewardId,
        String rewardTitle,
        String rewardImageUrl,
        Long userId,
        String userName,
        String status,
        int tokenCost,
        String comment,
        String adminComment,
        LocalDateTime requestedAt,
        LocalDateTime updatedAt,
        LocalDateTime fulfilledAt
) {
}
