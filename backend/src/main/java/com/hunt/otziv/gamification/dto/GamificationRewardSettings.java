package com.hunt.otziv.gamification.dto;

public record GamificationRewardSettings(
        boolean rewardsEnabled,
        boolean competitionEnabled,
        int levelXp,
        int tokenLevelStep,
        boolean slaEnabled,
        int controlTargetHours,
        int dayTargetPercent,
        int messageTargetMinutes,
        int messageHardMinutes,
        int leadTargetMinutes,
        int leadHardMinutes,
        int riskTargetMinutes,
        int riskHardMinutes,
        int defaultTargetMinutes,
        int defaultHardMinutes
) {
}
