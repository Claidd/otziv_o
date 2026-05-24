package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.config.settings.AppSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class TelegramReportScheduleSettingsService {

    public static final boolean DEFAULT_MORNING_ENABLED = true;
    public static final boolean DEFAULT_EVENING_ENABLED = true;
    public static final String DEFAULT_MORNING_TIME = "11:30";
    public static final String DEFAULT_EVENING_TIME = "22:00";
    public static final String DEFAULT_ZONE = "Asia/Irkutsk";

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final AppSettingService appSettingService;

    @Transactional(readOnly = true)
    public TelegramReportScheduleSettingsResponse settings() {
        return new TelegramReportScheduleSettingsResponse(
                appSettingService.getBoolean(
                        AppSettingService.TELEGRAM_REPORTS_MORNING_ENABLED,
                        DEFAULT_MORNING_ENABLED
                ),
                normalizeStoredTime(
                        appSettingService.getString(
                                AppSettingService.TELEGRAM_REPORTS_MORNING_TIME,
                                DEFAULT_MORNING_TIME
                        ),
                        DEFAULT_MORNING_TIME
                ),
                appSettingService.getBoolean(
                        AppSettingService.TELEGRAM_REPORTS_EVENING_ENABLED,
                        DEFAULT_EVENING_ENABLED
                ),
                normalizeStoredTime(
                        appSettingService.getString(
                                AppSettingService.TELEGRAM_REPORTS_EVENING_TIME,
                                DEFAULT_EVENING_TIME
                        ),
                        DEFAULT_EVENING_TIME
                ),
                normalizeStoredZone(
                        appSettingService.getString(AppSettingService.TELEGRAM_REPORTS_ZONE, DEFAULT_ZONE)
                ),
                appSettingService.getString(AppSettingService.TELEGRAM_REPORTS_MORNING_LAST_RUN_KEY, ""),
                appSettingService.getString(AppSettingService.TELEGRAM_REPORTS_EVENING_LAST_RUN_KEY, "")
        );
    }

    @Transactional
    public TelegramReportScheduleSettingsResponse updateSettings(TelegramReportScheduleSettingsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Укажите настройки рассылок");
        }

        TelegramReportScheduleSettingsResponse current = settings();
        boolean morningEnabled = request.morningEnabled() == null
                ? current.morningEnabled()
                : request.morningEnabled();
        boolean eveningEnabled = request.eveningEnabled() == null
                ? current.eveningEnabled()
                : request.eveningEnabled();
        String morningTime = StringUtils.hasText(request.morningTime())
                ? normalizeTime(request.morningTime(), "Укажите время утренней рассылки")
                : current.morningTime();
        String eveningTime = StringUtils.hasText(request.eveningTime())
                ? normalizeTime(request.eveningTime(), "Укажите время вечерней рассылки")
                : current.eveningTime();
        String zone = StringUtils.hasText(request.zone())
                ? normalizeZone(request.zone())
                : current.zone();

        appSettingService.setBoolean(AppSettingService.TELEGRAM_REPORTS_MORNING_ENABLED, morningEnabled);
        appSettingService.setString(AppSettingService.TELEGRAM_REPORTS_MORNING_TIME, morningTime);
        appSettingService.setBoolean(AppSettingService.TELEGRAM_REPORTS_EVENING_ENABLED, eveningEnabled);
        appSettingService.setString(AppSettingService.TELEGRAM_REPORTS_EVENING_TIME, eveningTime);
        appSettingService.setString(AppSettingService.TELEGRAM_REPORTS_ZONE, zone);

        return settings();
    }

    @Transactional
    public boolean claimMorningRun(String runKey) {
        return claimRun(AppSettingService.TELEGRAM_REPORTS_MORNING_LAST_RUN_KEY, runKey);
    }

    @Transactional
    public boolean claimEveningRun(String runKey) {
        return claimRun(AppSettingService.TELEGRAM_REPORTS_EVENING_LAST_RUN_KEY, runKey);
    }

    private boolean claimRun(String settingKey, String runKey) {
        if (!StringUtils.hasText(runKey)) {
            throw new IllegalArgumentException("Run key is required");
        }

        String normalizedRunKey = runKey.trim();
        String lastRunKey = appSettingService.getString(settingKey, "");
        if (normalizedRunKey.equals(lastRunKey)) {
            return false;
        }

        appSettingService.setString(settingKey, normalizedRunKey);
        return true;
    }

    private String normalizeStoredTime(String value, String fallback) {
        try {
            return normalizeTime(value, "Некорректное время рассылки");
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private String normalizeTime(String value, String message) {
        try {
            return LocalTime.parse(value.trim())
                    .truncatedTo(ChronoUnit.MINUTES)
                    .format(TIME_FORMATTER);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(message);
        }
    }

    private String normalizeStoredZone(String value) {
        try {
            return normalizeZone(value);
        } catch (IllegalArgumentException ignored) {
            return DEFAULT_ZONE;
        }
    }

    private String normalizeZone(String value) {
        try {
            return ZoneId.of(value.trim()).getId();
        } catch (DateTimeException | NullPointerException exception) {
            throw new IllegalArgumentException("Укажите корректный часовой пояс");
        }
    }
}
