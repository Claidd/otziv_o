package com.hunt.otziv.gamification.dto;

public record GamificationTokenGrantRequest(Long userId, Integer amount, String description) {
}
