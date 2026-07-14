package com.hunt.otziv.gamification.dto;

public record GamificationRewardRequest(
        String code,
        String title,
        String description,
        String rewardType,
        String icon,
        String imageUrl,
        Integer tokenCost,
        Integer requiredLevel,
        Integer stockQuantity,
        Boolean active,
        Integer sortOrder
) {
}
