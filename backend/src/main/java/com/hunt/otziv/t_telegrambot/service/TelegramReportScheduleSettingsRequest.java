package com.hunt.otziv.t_telegrambot.service;

public record TelegramReportScheduleSettingsRequest(
        Boolean morningEnabled,
        String morningTime,
        Boolean eveningEnabled,
        String eveningTime,
        String zone
) {
}
