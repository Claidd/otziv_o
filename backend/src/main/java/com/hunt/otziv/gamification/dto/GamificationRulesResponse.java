package com.hunt.otziv.gamification.dto;

import java.util.List;

public record GamificationRulesResponse(
        List<GamificationRuleResponse> rules
) {
}
