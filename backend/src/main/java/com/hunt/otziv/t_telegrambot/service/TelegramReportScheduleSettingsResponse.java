package com.hunt.otziv.t_telegrambot.service;

public record TelegramReportScheduleSettingsResponse(
        boolean morningEnabled,
        String morningTime,
        boolean eveningEnabled,
        String eveningTime,
        String zone,
        String morningLastRunKey,
        String eveningLastRunKey
) {
}
