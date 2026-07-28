package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Resolves every workload-shadow wall-clock timestamp in the same configurable
 * business zone. The supplied clock keeps tests deterministic and provides a safe
 * fallback if a setting was manually corrupted outside the validated settings UI.
 */
public final class WorkloadShadowBusinessTime {

    public static final String BUSINESS_ZONE_SETTING = "workload.shadow.business-zone";

    private WorkloadShadowBusinessTime() {
    }

    public static LocalDateTime now(AppSettingService settings, Clock clock) {
        return LocalDateTime.now(clock.withZone(resolveZone(settings, clock)));
    }

    public static LocalDate today(AppSettingService settings, Clock clock) {
        return LocalDate.now(clock.withZone(resolveZone(settings, clock)));
    }

    static ZoneId resolveZone(AppSettingService settings, Clock clock) {
        ZoneId fallback = clock == null ? ZoneId.systemDefault() : clock.getZone();
        String configured = settings == null
                ? null
                : settings.getString(BUSINESS_ZONE_SETTING, fallback.getId());
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        try {
            return ZoneId.of(configured.trim());
        } catch (DateTimeException ignored) {
            return fallback;
        }
    }
}
