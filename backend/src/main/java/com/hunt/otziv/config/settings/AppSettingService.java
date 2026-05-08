package com.hunt.otziv.config.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppSettingService {

    public static final String NAGUL_COOLDOWN_MINUTES = "nagul.cooldown.minutes";
    public static final String NAGUL_LOOKAHEAD_DAYS = "nagul.lookahead.days";

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

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
