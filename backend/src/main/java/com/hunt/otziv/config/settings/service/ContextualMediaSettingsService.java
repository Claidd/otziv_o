package com.hunt.otziv.config.settings.service;

import com.hunt.otziv.config.settings.api.ContextualMediaSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContextualMediaSettingsService implements ContextualMediaSettings {
    private final AppSettingService settings;
    public boolean enabled() { return settings.getBoolean("worker.thematic-notifications.enabled", true)
            && settings.getBoolean("notification-media.contextual.enabled", true); }
    public int maxPerDay() { return Math.max(1, Math.min(5, settings.getInt("worker.thematic-notifications.max-per-day", 2))); }
}
