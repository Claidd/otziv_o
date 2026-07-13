package com.hunt.otziv.gamification.dto;

public record GamificationWalletResponse(
        long lifetimeXp,
        int level,
        int tokens,
        int nextTokenLevel
) {
}
