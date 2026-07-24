package com.hunt.otziv.reputationai.application;

import com.hunt.otziv.config.settings.service.AppSettingService;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

@Service
public class ReputationAiProviderSelectionService {

    public static final String SETTING_KEY = "reputation.ai.provider";
    public static final String DEFAULT_PROVIDER = "deepseek";

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("deepseek", "yandexgpt", "openai");

    private final AppSettingService appSettingService;

    public ReputationAiProviderSelectionService(AppSettingService appSettingService) {
        this.appSettingService = appSettingService;
    }

    public String activeProvider() {
        String configured = appSettingService.getString(SETTING_KEY, DEFAULT_PROVIDER);
        try {
            return normalize(configured);
        } catch (IllegalArgumentException ignored) {
            return DEFAULT_PROVIDER;
        }
    }

    public String select(String provider) {
        String normalized = normalize(provider);
        appSettingService.setString(SETTING_KEY, normalized);
        return normalized;
    }

    private String normalize(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "deep-seek" -> "deepseek";
            case "yandex", "yandex-gpt" -> "yandexgpt";
            case "open-ai" -> "openai";
            default -> normalized;
        };
        if (!SUPPORTED_PROVIDERS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported AI provider: " + provider);
        }
        return normalized;
    }
}
