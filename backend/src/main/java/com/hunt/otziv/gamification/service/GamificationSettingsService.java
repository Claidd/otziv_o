package com.hunt.otziv.gamification.service;

import com.hunt.otziv.gamification.dto.GamificationSettingsRequest;
import com.hunt.otziv.gamification.dto.GamificationSettingsResponse;
import com.hunt.otziv.gamification.model.GamificationSetting;
import com.hunt.otziv.gamification.repository.GamificationSettingRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GamificationSettingsService {

    public static final String ENABLED = "gamification.enabled";
    public static final String WORKER_ENABLED = "gamification.worker.enabled";
    public static final String MANAGER_ENABLED = "gamification.manager.enabled";
    public static final String OPERATOR_ENABLED = "gamification.operator.enabled";
    public static final String MARKETOLOG_ENABLED = "gamification.marketolog.enabled";
    public static final String SHOW_IN_CABINET = "gamification.show-in-cabinet";
    public static final String SHOW_IN_SCORE = "gamification.show-in-score";
    public static final String EVENTS_ENABLED = "gamification.events-enabled";
    public static final String SHADOW_SCORING_ENABLED = "gamification.shadow-scoring.enabled";

    private static final Map<String, Boolean> DEFAULTS = defaults();

    private final GamificationSettingRepository repository;

    @Transactional(readOnly = true)
    public GamificationSettingsResponse getSettings() {
        Map<String, GamificationSetting> settings = readSettings();
        return response(settings);
    }

    @Transactional
    public GamificationSettingsResponse updateSettings(GamificationSettingsRequest request) {
        GamificationSettingsRequest safeRequest = request == null
                ? new GamificationSettingsRequest(null, null, null, null, null, null, null, null, null)
                : request;
        Map<String, Boolean> values = new LinkedHashMap<>();
        values.put(ENABLED, value(safeRequest.enabled(), ENABLED));
        values.put(WORKER_ENABLED, value(safeRequest.workerEnabled(), WORKER_ENABLED));
        values.put(MANAGER_ENABLED, value(safeRequest.managerEnabled(), MANAGER_ENABLED));
        values.put(OPERATOR_ENABLED, value(safeRequest.operatorEnabled(), OPERATOR_ENABLED));
        values.put(MARKETOLOG_ENABLED, value(safeRequest.marketologEnabled(), MARKETOLOG_ENABLED));
        values.put(SHOW_IN_CABINET, value(safeRequest.showInCabinet(), SHOW_IN_CABINET));
        values.put(SHOW_IN_SCORE, value(safeRequest.showInScore(), SHOW_IN_SCORE));
        values.put(EVENTS_ENABLED, value(safeRequest.eventsEnabled(), EVENTS_ENABLED));
        values.put(SHADOW_SCORING_ENABLED, value(safeRequest.shadowScoringEnabled(), SHADOW_SCORING_ENABLED));

        for (Map.Entry<String, Boolean> entry : values.entrySet()) {
            GamificationSetting setting = repository.findById(entry.getKey())
                    .orElseGet(() -> GamificationSetting.builder().key(entry.getKey()).build());
            setting.setValue(String.valueOf(entry.getValue()));
            repository.save(setting);
        }

        return response(readSettings());
    }

    @Transactional(readOnly = true)
    public boolean isEventsEnabledForRole(String role) {
        Map<String, GamificationSetting> settings = readSettings();
        if (!booleanValue(settings, ENABLED) || !booleanValue(settings, EVENTS_ENABLED)) {
            return false;
        }
        if (GamificationEventService.ROLE_WORKER.equals(role)) {
            return booleanValue(settings, WORKER_ENABLED);
        }
        if (GamificationEventService.ROLE_MANAGER.equals(role)) {
            return booleanValue(settings, MANAGER_ENABLED);
        }
        return true;
    }

    @Transactional(readOnly = true)
    public boolean isShadowScoringEnabled() {
        Map<String, GamificationSetting> settings = readSettings();
        return booleanValue(settings, ENABLED) && booleanValue(settings, SHADOW_SCORING_ENABLED);
    }

    @Transactional(readOnly = true)
    public boolean isCabinetVisibleForRole(String role) {
        Map<String, GamificationSetting> settings = readSettings();
        if (!booleanValue(settings, ENABLED)
                || !booleanValue(settings, SHOW_IN_CABINET)
                || !booleanValue(settings, SHADOW_SCORING_ENABLED)) {
            return false;
        }

        String normalizedRole = normalizeRole(role);
        if (GamificationEventService.ROLE_WORKER.equals(normalizedRole)) {
            return booleanValue(settings, WORKER_ENABLED);
        }
        if (GamificationEventService.ROLE_MANAGER.equals(normalizedRole)) {
            return booleanValue(settings, MANAGER_ENABLED);
        }
        if ("OPERATOR".equals(normalizedRole)) {
            return booleanValue(settings, OPERATOR_ENABLED);
        }
        if ("MARKETOLOG".equals(normalizedRole)) {
            return booleanValue(settings, MARKETOLOG_ENABLED);
        }
        return true;
    }

    private Map<String, GamificationSetting> readSettings() {
        Map<String, GamificationSetting> result = new LinkedHashMap<>();
        repository.findAllById(DEFAULTS.keySet()).forEach(setting -> result.put(setting.getKey(), setting));
        return result;
    }

    private GamificationSettingsResponse response(Map<String, GamificationSetting> settings) {
        return new GamificationSettingsResponse(
                booleanValue(settings, ENABLED),
                booleanValue(settings, WORKER_ENABLED),
                booleanValue(settings, MANAGER_ENABLED),
                booleanValue(settings, OPERATOR_ENABLED),
                booleanValue(settings, MARKETOLOG_ENABLED),
                booleanValue(settings, SHOW_IN_CABINET),
                booleanValue(settings, SHOW_IN_SCORE),
                booleanValue(settings, EVENTS_ENABLED),
                booleanValue(settings, SHADOW_SCORING_ENABLED),
                updatedAt(settings)
        );
    }

    private boolean value(Boolean value, String key) {
        return value == null ? DEFAULTS.getOrDefault(key, false) : value;
    }

    private boolean booleanValue(Map<String, GamificationSetting> settings, String key) {
        GamificationSetting setting = settings.get(key);
        if (setting == null) {
            return DEFAULTS.getOrDefault(key, false);
        }

        String value = setting.getValue();
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value)) {
            return false;
        }
        return DEFAULTS.getOrDefault(key, false);
    }

    private LocalDateTime updatedAt(Map<String, GamificationSetting> settings) {
        return settings.values().stream()
                .map(GamificationSetting::getUpdatedAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "";
        }
        String value = role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role;
        return value.toUpperCase(java.util.Locale.ROOT);
    }

    private static Map<String, Boolean> defaults() {
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        defaults.put(ENABLED, false);
        defaults.put(WORKER_ENABLED, true);
        defaults.put(MANAGER_ENABLED, true);
        defaults.put(OPERATOR_ENABLED, true);
        defaults.put(MARKETOLOG_ENABLED, true);
        defaults.put(SHOW_IN_CABINET, false);
        defaults.put(SHOW_IN_SCORE, false);
        defaults.put(EVENTS_ENABLED, false);
        defaults.put(SHADOW_SCORING_ENABLED, false);
        return Map.copyOf(defaults);
    }
}
