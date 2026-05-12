package com.hunt.otziv.reputationai.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

public enum ContentPackProfile {
    ECONOMY(
            "economy",
            "Эконом",
            "gpt-5.4-mini",
            "Дешевле для быстрых вариантов пакета по готовому отчёту.",
            Duration.ofMinutes(4),
            10000,
            "low"
    ),
    QUALITY(
            "quality",
            "Качество",
            "gpt-5.5",
            "Основной режим: сильный маркетинговый пакет без web search.",
            Duration.ofMinutes(6),
            18000,
            "low"
    ),
    MAXIMUM(
            "maximum",
            "Максимум",
            "gpt-5.5",
            "Самый подробный маркетинговый пакет по глубокому отчёту.",
            Duration.ofMinutes(8),
            26000,
            "medium"
    );

    private final String key;
    private final String label;
    private final String model;
    private final String description;
    private final Duration timeout;
    private final int maxOutputTokens;
    private final String reasoningEffort;

    ContentPackProfile(
            String key,
            String label,
            String model,
            String description,
            Duration timeout,
            int maxOutputTokens,
            String reasoningEffort
    ) {
        this.key = key;
        this.label = label;
        this.model = model;
        this.description = description;
        this.timeout = timeout;
        this.maxOutputTokens = maxOutputTokens;
        this.reasoningEffort = reasoningEffort;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String model() {
        return model;
    }

    public String description() {
        return description;
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public String reasoningEffort() {
        return reasoningEffort;
    }

    public static ContentPackProfile fromKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(profile -> profile.key.equals(normalized))
                .findFirst()
                .orElse(null);
    }

    public static List<ContentPackProfile> all() {
        return List.of(values());
    }
}
