package com.hunt.otziv.config.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppSettingService {

    public static final String NAGUL_COOLDOWN_MINUTES = "nagul.cooldown.minutes";
    public static final String NAGUL_LOOKAHEAD_DAYS = "nagul.lookahead.days";
    public static final String TELEGRAM_REPORTS_MORNING_ENABLED = "telegram.reports.morning.enabled";
    public static final String TELEGRAM_REPORTS_MORNING_TIME = "telegram.reports.morning.time";
    public static final String TELEGRAM_REPORTS_MORNING_LAST_RUN_KEY = "telegram.reports.morning.last-run-key";
    public static final String TELEGRAM_REPORTS_EVENING_ENABLED = "telegram.reports.evening.enabled";
    public static final String TELEGRAM_REPORTS_EVENING_TIME = "telegram.reports.evening.time";
    public static final String TELEGRAM_REPORTS_EVENING_LAST_RUN_KEY = "telegram.reports.evening.last-run-key";
    public static final String TELEGRAM_REPORTS_ZONE = "telegram.reports.zone";
    public static final String WHATSAPP_GROUP_SYNC_ENABLED = "whatsapp.group-sync.enabled";
    public static final String WHATSAPP_GROUP_SYNC_INTERVAL_MINUTES = "whatsapp.group-sync.interval-minutes";
    public static final String WHATSAPP_GROUP_SYNC_LAST_RUN_AT = "whatsapp.group-sync.last-run-at";
    public static final String WHATSAPP_GROUP_SYNC_LAST_LINKED_COUNT = "whatsapp.group-sync.last-linked-count";
    public static final String CLIENT_PUBLICATION_PROGRESS_REPORTS_ENABLED = "client.publication-progress-reports.enabled";
    public static final String ARCHIVE_ORDERS_RETENTION_DAYS = "archive.orders.retention.days";
    public static final String ARCHIVE_ORDERS_BATCH_SIZE = "archive.orders.batch.size";
    public static final String ARCHIVE_ORDERS_APPLY_ENABLED = "archive.orders.apply.enabled";
    public static final String ARCHIVE_ORDERS_SCHEDULE_WORKER_ENABLED = "archive.orders.schedule.worker.enabled";
    public static final String ARCHIVE_ORDERS_SCHEDULE_ENABLED = "archive.orders.schedule.enabled";
    public static final String ARCHIVE_ORDERS_SCHEDULE_CRON = "archive.orders.schedule.cron";
    public static final String ARCHIVE_ORDERS_SCHEDULE_ZONE = "archive.orders.schedule.zone";
    public static final String ARCHIVE_ORDERS_SCHEDULE_LAST_RUN_KEY = "archive.orders.schedule.last-run-key";
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
