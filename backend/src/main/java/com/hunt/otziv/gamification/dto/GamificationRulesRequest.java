package com.hunt.otziv.gamification.dto;

import java.util.List;

public record GamificationRulesRequest(
        List<GamificationRuleRequest> rules
) {
}
