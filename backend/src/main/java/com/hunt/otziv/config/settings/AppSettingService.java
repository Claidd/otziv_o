package com.hunt.otziv.config.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppSettingService {

    public static final String NAGUL_COOLDOWN_MINUTES = "nagul.cooldown.minutes";
    public static final String NAGUL_LOOKAHEAD_DAYS = "nagul.lookahead.days";
    public static final String ARCHIVE_ORDERS_RETENTION_DAYS = "archive.orders.retention.days";
    public static final String ARCHIVE_ORDERS_BATCH_SIZE = "archive.orders.batch.size";
    public static final String ARCHIVE_ORDERS_SCHEDULE_ENABLED = "archive.orders.schedule.enabled";
    public static final String ARCHIVE_ORDERS_RUN_MODE = "archive.orders.run.mode";
    public static final String ARCHIVE_ORDERS_REASON = "archive.orders.reason";

    private final AppSettingRepository repository;

    @Transactional(readOnly = true)
    public int getInt(String key, int fallback) {
        return repository.findById(key)
                .map(AppSetting::getValue)
                .map(value -> parseInt(value, fallback))
                .orElse(fallback);
    }

    @Transactional
    public int setInt(String key, int value) {
        AppSetting setting = repository.findById(key)
                .orElseGet(() -> AppSetting.builder().key(key).build());
        setting.setValue(String.valueOf(value));
        repository.save(setting);
        return value;
    }

    @Transactional(readOnly = true)
    public boolean getBoolean(String key, boolean fallback) {
        return repository.findById(key)
                .map(AppSetting::getValue)
                .map(value -> parseBoolean(value, fallback))
                .orElse(fallback);
    }

    @Transactional
    public boolean setBoolean(String key, boolean value) {
        setString(key, String.valueOf(value));
        return value;
    }

    @Transactional(readOnly = true)
    public String getString(String key, String fallback) {
        return repository.findById(key)
                .map(AppSetting::getValue)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(fallback);
    }

    @Transactional
    public String setString(String key, String value) {
        AppSetting setting = repository.findById(key)
                .orElseGet(() -> AppSetting.builder().key(key).build());
        setting.setValue(value);
        repository.save(setting);
        return value;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean parseBoolean(String value, boolean fallback) {
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value)) {
            return false;
        }
        return fallback;
    }
}
