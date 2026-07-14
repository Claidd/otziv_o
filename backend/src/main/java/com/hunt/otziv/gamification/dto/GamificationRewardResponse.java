package com.hunt.otziv.gamification.dto;

import java.time.LocalDateTime;

public record GamificationRewardResponse(
        Long id,
        String code,
        String title,
        String description,
        String rewardType,
        String icon,
        String imageUrl,
        int tokenCost,
        int requiredLevel,
        Integer stockQuantity,
        boolean active,
        int sortOrder,
        boolean claimable,
        String lockedReason,
        LocalDateTime updatedAt
) {
}
