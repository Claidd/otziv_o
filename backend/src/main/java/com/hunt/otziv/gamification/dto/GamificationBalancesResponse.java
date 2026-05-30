package com.hunt.otziv.gamification.dto;

import java.time.LocalDate;
import java.util.List;

public record GamificationBalancesResponse(
        LocalDate from,
        LocalDate to,
        int days,
        List<GamificationBalanceResponse> balances
) {
}
