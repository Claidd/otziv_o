package com.hunt.otziv.workload_shadow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsRequest;
import com.hunt.otziv.workload_shadow.dto.WorkloadShadowSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowSettingsRepository;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class WorkloadShadowSettingsService {

    public static final String MODE_SHADOW = "SHADOW";
    public static final int HARD_MINIMUM_WALK_MINUTES = 3;

    static final String PREFIX = "workload.shadow.";
    static final String REVISION_KEY = PREFIX + "settings-revision";

    private final WorkloadShadowSettingsRepository repository;
    private final BusinessAuditService businessAuditService;
    private final ObjectMapper objectMapper;
    private final AppSettingService appSettingService;

    @Transactional(readOnly = true)
    public WorkloadShadowSettingsResponse current() {
        SettingsValues settings = loadValues();
        int configuredMinimum = settings.getInt(
                "walk-minimum-minutes-per-card",
                HARD_MINIMUM_WALK_MINUTES
        );
        int effectiveMinimum = Math.max(HARD_MINIMUM_WALK_MINUTES, configuredMinimum);
        int walkMinutes = Math.max(
                effectiveMinimum,
                settings.getInt("walk-minutes-per-card", 4)
        );
        return new WorkloadShadowSettingsResponse(
                MODE_SHADOW,
                false,
                settings.getBoolean("observation-enabled", true),
                settings.getBoolean("group-notifications-enabled", true),
                settings.getInt("scheduler-interval-minutes", 10),
                settings.getInt("near-end-interval-minutes", 5),
                settings.getInt("near-end-window-minutes", 120),
                settings.getString("business-zone", "Asia/Irkutsk"),
                settings.getString("shift-start", "10:00"),
                settings.getString("shift-end", "23:00"),
                walkMinutes,
                effectiveMinimum,
                settings.getInt("new-minutes-per-card", 5),
                settings.getInt("correction-minutes-per-order", 10),
                settings.getInt("publish-minutes-per-card", 3),
                settings.getInt("recovery-minutes-per-task", 10),
                settings.getInt("bad-minutes-per-task", 10),
                settings.getBoolean("adaptive-estimates-enabled", true),
                settings.getInt("adaptive-minimum-samples", 30),
                settings.getInt("lookback-days", 30),
                settings.getInt("allowed-failure-days", 3),
                settings.getInt("recipient-minimum-rating", 85),
                settings.getInt("recipient-minimum-100-rate", 80),
                settings.getInt("recipient-maximum-failure-days", 2),
                settings.getInt("fourth-failure-percent", 15),
                settings.getInt("fourth-failure-max-companies", 1),
                settings.getInt("fifth-failure-percent", 25),
                settings.getInt("fifth-failure-max-companies", 2),
                settings.getInt("sixth-failure-percent", 30),
                settings.getInt("sixth-failure-max-companies", 3),
                settings.getInt("freeze-earn-days", 14),
                settings.getInt("freeze-max-credits", 2),
                settings.getInt("alert-cooldown-minutes", 60),
                settings.getInt("run-retention-days", 30),
                settings.getInt("daily-retention-days", 400),
                settings.getInt("event-retention-days", 90),
                settings.getInt("decision-retention-days", 60),
                settings.getInt("stale-run-minutes", 30),
                settings.getInt("notification-batch-size", 10),
                settings.getInt("notification-max-attempts", 8),
                settings.getInt("notification-lease-minutes", 5),
                settings.getInt("notification-retry-base-minutes", 1),
                settings.getInt("maintenance-batch-size", 1000),
                settings.getLong("settings-revision", 1)
        );
    }

    @Transactional
    public WorkloadShadowSettingsResponse update(WorkloadShadowSettingsRequest request) {
        if (request == null) {
            throw badRequest("Настройки режима наблюдения не переданы");
        }
        validate(request);
        WorkloadShadowSettingsResponse before = current();
        if (request.revision() != null
                && request.revision().longValue() != before.revision()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Настройки уже изменены другим пользователем. Обновите страницу."
            );
        }

        long nextRevision = Math.addExact(before.revision(), 1);
        Map<String, String> updatedValues = requestedValues(request, nextRevision);
        int updatedRows = repository.updateAllWithRevision(
                toJson(updatedValues),
                PREFIX,
                REVISION_KEY,
                before.revision()
        );
        if (updatedRows == 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Настройки уже изменены другим пользователем. Обновите страницу."
            );
        }
        if (updatedRows != updatedValues.size()) {
            throw new IllegalStateException(
                    "Неполный набор workload shadow настроек: ожидалось "
                            + updatedValues.size() + ", обновлено " + updatedRows
            );
        }

        WorkloadShadowSettingsResponse after = current();
        if (after.revision() != nextRevision) {
            throw new IllegalStateException(
                    "Ревизия workload shadow не была обновлена атомарно"
            );
        }
        invalidateLegacyCacheAfterCommit();
        businessAuditService.recordSafely(
                "UPDATE_WORKLOAD_SHADOW_SETTINGS",
                "WORKLOAD_SHADOW_SETTINGS",
                "global",
                null,
                null,
                before,
                after,
                "Изменены параметры системы выравнивания нагрузки в режиме наблюдения; боевое применение заблокировано"
        );
        return after;
    }

    private SettingsValues loadValues() {
        Map<String, String> values = new LinkedHashMap<>();
        List<WorkloadShadowSettingsRepository.SettingProjection> rows =
                repository.findAllByPrefix(PREFIX);
        if (rows != null) {
            for (WorkloadShadowSettingsRepository.SettingProjection row : rows) {
                if (row == null
                        || row.getSettingKey() == null
                        || row.getSettingValue() == null
                        || !row.getSettingKey().startsWith(PREFIX)) {
                    continue;
                }
                values.put(row.getSettingKey(), row.getSettingValue());
            }
        }
        return new SettingsValues(values);
    }

    private Map<String, String> requestedValues(
            WorkloadShadowSettingsRequest request,
            long nextRevision
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        put(values, "observation-enabled", request.observationEnabled());
        put(values, "apply-enabled", false);
        put(values, "group-notifications-enabled", request.groupNotificationsEnabled());
        put(values, "scheduler-interval-minutes", request.schedulerIntervalMinutes());
        put(values, "near-end-interval-minutes", request.nearEndIntervalMinutes());
        put(values, "near-end-window-minutes", request.nearEndWindowMinutes());
        put(values, "business-zone", request.businessZone().trim());
        put(values, "shift-start", normalizedTime(request.shiftStart()));
        put(values, "shift-end", normalizedTime(request.shiftEnd()));
        put(values, "walk-minutes-per-card", request.walkMinutesPerCard());
        put(values, "walk-minimum-minutes-per-card", request.walkMinimumMinutesPerCard());
        put(values, "new-minutes-per-card", request.newMinutesPerCard());
        put(values, "correction-minutes-per-order", request.correctionMinutesPerOrder());
        put(values, "publish-minutes-per-card", request.publishMinutesPerCard());
        put(values, "recovery-minutes-per-task", request.recoveryMinutesPerTask());
        put(values, "bad-minutes-per-task", request.badMinutesPerTask());
        put(values, "adaptive-estimates-enabled", request.adaptiveEstimatesEnabled());
        put(values, "adaptive-minimum-samples", request.adaptiveMinimumSamples());
        put(values, "lookback-days", request.lookbackDays());
        put(values, "allowed-failure-days", request.allowedFailureDays());
        put(values, "recipient-minimum-rating", request.recipientMinimumRating());
        put(
                values,
                "recipient-minimum-100-rate",
                request.recipientMinimumHundredPercentRate()
        );
        put(
                values,
                "recipient-maximum-failure-days",
                request.recipientMaximumFailureDays()
        );
        put(values, "fourth-failure-percent", request.fourthFailurePercent());
        put(values, "fourth-failure-max-companies", request.fourthFailureMaxCompanies());
        put(values, "fifth-failure-percent", request.fifthFailurePercent());
        put(values, "fifth-failure-max-companies", request.fifthFailureMaxCompanies());
        put(values, "sixth-failure-percent", request.sixthFailurePercent());
        put(values, "sixth-failure-max-companies", request.sixthFailureMaxCompanies());
        put(values, "freeze-earn-days", request.freezeEarnDays());
        put(values, "freeze-max-credits", request.freezeMaxCredits());
        put(values, "alert-cooldown-minutes", request.alertCooldownMinutes());
        put(values, "run-retention-days", request.runRetentionDays());
        put(values, "daily-retention-days", request.dailyRetentionDays());
        put(values, "event-retention-days", request.eventRetentionDays());
        put(values, "decision-retention-days", request.decisionRetentionDays());
        put(values, "stale-run-minutes", request.staleRunMinutes());
        put(values, "notification-batch-size", request.notificationBatchSize());
        put(values, "notification-max-attempts", request.notificationMaxAttempts());
        put(values, "notification-lease-minutes", request.notificationLeaseMinutes());
        put(
                values,
                "notification-retry-base-minutes",
                request.notificationRetryBaseMinutes()
        );
        put(values, "maintenance-batch-size", request.maintenanceBatchSize());
        put(values, "settings-revision", nextRevision);
        return values;
    }

    private void put(Map<String, String> values, String suffix, Object value) {
        values.put(key(suffix), String.valueOf(value));
    }

    private String toJson(Map<String, String> values) {
        List<SettingWrite> payload = values.entrySet().stream()
                .map(entry -> new SettingWrite(entry.getKey(), entry.getValue()))
                .toList();
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Не удалось сериализовать настройки workload shadow",
                    exception
            );
        }
    }

    private void invalidateLegacyCacheAfterCommit() {
        Runnable invalidation = () -> appSettingService.invalidateByPrefix(PREFIX);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidation.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        invalidation.run();
                    }
                }
        );
    }

    public ZoneId zone(WorkloadShadowSettingsResponse value) {
        return ZoneId.of(value.businessZone());
    }

    public LocalTime shiftStart(WorkloadShadowSettingsResponse value) {
        return LocalTime.parse(value.shiftStart());
    }

    public LocalTime shiftEnd(WorkloadShadowSettingsResponse value) {
        return LocalTime.parse(value.shiftEnd());
    }

    private void validate(WorkloadShadowSettingsRequest request) {
        if (request.applyEnabled() || (request.mode() != null && !MODE_SHADOW.equalsIgnoreCase(request.mode().trim()))) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED,
                    "Боевой режим недоступен: система запущена только в режиме наблюдения"
            );
        }
        requireText(request.businessZone(), "Часовой пояс");
        try {
            ZoneId.of(request.businessZone().trim());
        } catch (DateTimeException exception) {
            throw badRequest("Неизвестный часовой пояс: " + request.businessZone());
        }
        LocalTime shiftStart = parseTime(request.shiftStart(), "Начало смены");
        LocalTime shiftEnd = parseTime(request.shiftEnd(), "Окончание смены");
        if (!shiftEnd.isAfter(shiftStart)) {
            throw badRequest("Окончание смены должно быть позже начала смены в пределах одного рабочего дня");
        }

        range(request.schedulerIntervalMinutes(), 5, 60, "Обычный интервал пересчёта");
        range(request.nearEndIntervalMinutes(), 5, request.schedulerIntervalMinutes(), "Интервал пересчёта к концу смены");
        range(request.nearEndWindowMinutes(), 15, 360, "Окно усиленного наблюдения");
        range(request.walkMinimumMinutesPerCard(), HARD_MINIMUM_WALK_MINUTES, 30, "Минимум минут на одну карточку выгула");
        range(request.walkMinutesPerCard(), request.walkMinimumMinutesPerCard(), 30, "Минут на одну карточку выгула");
        range(request.newMinutesPerCard(), 1, 120, "Минут на новую карточку");
        range(request.correctionMinutesPerOrder(), 1, 240, "Минут на один заказ в коррекции");
        range(request.publishMinutesPerCard(), 1, 60, "Минут на публикацию карточки");
        range(request.recoveryMinutesPerTask(), 1, 240, "Минут на восстановление");
        range(request.badMinutesPerTask(), 1, 240, "Минут на задачу «Плохие»");
        range(request.adaptiveMinimumSamples(), 10, 10_000, "Минимум замеров для статистики");
        range(request.lookbackDays(), 7, 90, "Период анализа");
        range(request.allowedFailureDays(), 0, 15, "Допустимые дни ниже 100%");
        range(request.recipientMinimumRating(), 0, 100, "Минимальный рейтинг получателя");
        range(request.recipientMinimumHundredPercentRate(), 0, 100, "Минимальная доля дней со 100%");
        range(
                request.recipientMaximumFailureDays(),
                0,
                31,
                "Максимум неуспешных дней получателя в текущем месяце"
        );
        range(request.fourthFailurePercent(), 1, 100, "Процент передачи на четвёртом случае");
        range(request.fifthFailurePercent(), request.fourthFailurePercent(), 100, "Процент передачи на пятом случае");
        range(request.sixthFailurePercent(), request.fifthFailurePercent(), 100, "Процент передачи с шестого случая");
        range(request.fourthFailureMaxCompanies(), 1, 20, "Лимит компаний на четвёртом случае");
        range(request.fifthFailureMaxCompanies(), request.fourthFailureMaxCompanies(), 20, "Лимит компаний на пятом случае");
        range(request.sixthFailureMaxCompanies(), request.fifthFailureMaxCompanies(), 20, "Лимит компаний с шестого случая");
        range(request.freezeEarnDays(), 1, 60, "Дней для получения заморозки");
        range(request.freezeMaxCredits(), 0, 10, "Максимум заморозок");
        range(request.alertCooldownMinutes(), 5, 10_080, "Пауза повторного предупреждения");
        range(request.runRetentionDays(), 7, 365, "Хранение запусков");
        range(request.dailyRetentionDays(), 31, 3650, "Хранение дневных итогов");
        range(request.eventRetentionDays(), 7, 3650, "Хранение событий");
        range(request.decisionRetentionDays(), 7, 365, "Хранение дневных решений");
        range(request.staleRunMinutes(), 5, 240, "Порог зависшего запуска");
        range(request.notificationBatchSize(), 1, 25, "Размер пачки Telegram-уведомлений");
        range(request.notificationMaxAttempts(), 1, 20, "Максимум попыток Telegram-уведомления");
        range(request.notificationLeaseMinutes(), 1, 30, "Аренда Telegram-уведомления");
        range(request.notificationRetryBaseMinutes(), 1, 60, "Базовая пауза Telegram-повтора");
        range(request.maintenanceBatchSize(), 100, 5000, "Размер пачки самообслуживания");
    }

    private LocalTime parseTime(String value, String label) {
        requireText(value, label);
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeException exception) {
            throw badRequest(label + ": используйте формат ЧЧ:ММ");
        }
    }

    private String normalizedTime(String value) {
        return parseTime(value, "Время").withSecond(0).withNano(0).toString();
    }

    private void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw badRequest(label + " не указан");
        }
    }

    private void range(int value, int minimum, int maximum, String label) {
        if (value < minimum || value > maximum) {
            throw badRequest(label + ": допустимо от " + minimum + " до " + maximum);
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private String key(String suffix) {
        return PREFIX + suffix;
    }

    private record SettingWrite(String settingKey, String settingValue) {
    }

    private record SettingsValues(Map<String, String> values) {

        private int getInt(String suffix, int fallback) {
            String value = value(suffix);
            if (value == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private long getLong(String suffix, long fallback) {
            String value = value(suffix);
            if (value == null) {
                return fallback;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private boolean getBoolean(String suffix, boolean fallback) {
            String value = value(suffix);
            if (value == null) {
                return fallback;
            }
            String normalized = value.trim();
            if ("true".equalsIgnoreCase(normalized)
                    || "1".equals(normalized)
                    || "yes".equalsIgnoreCase(normalized)) {
                return true;
            }
            if ("false".equalsIgnoreCase(normalized)
                    || "0".equals(normalized)
                    || "no".equalsIgnoreCase(normalized)) {
                return false;
            }
            return fallback;
        }

        private String getString(String suffix, String fallback) {
            String value = value(suffix);
            if (value == null || value.trim().isEmpty()) {
                return fallback;
            }
            return value.trim();
        }

        private String value(String suffix) {
            return values.get(PREFIX + suffix);
        }
    }
}
