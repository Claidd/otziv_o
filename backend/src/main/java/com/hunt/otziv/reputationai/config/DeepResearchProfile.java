package com.hunt.otziv.reputationai.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

public enum DeepResearchProfile {
    ECONOMY(
            "economy",
            "Быстро",
            "gpt-5.4-mini",
            "Короткий и дешёвый отчёт для быстрой проверки маршрута и фактов.",
            Duration.ofMinutes(5),
            10,
            6000,
            "low",
            "low"
    ),
    QUALITY(
            "quality",
            "Баланс",
            "gpt-5.5",
            "Основной режим: нормальный отчёт с web search и источниками.",
            Duration.ofMinutes(8),
            32,
            12000,
            "low",
            "medium"
    ),
    MAXIMUM(
            "maximum",
            "Максимум",
            "gpt-5.5",
            "Глубокий отчёт с усиленным reasoning и большим запасом контекста.",
            Duration.ofMinutes(12),
            48,
            16000,
            "medium",
            "medium"
    );

    private final String key;
    private final String label;
    private final String model;
    private final String description;
    private final Duration timeout;
    private final int maxToolCalls;
    private final int maxOutputTokens;
    private final String reasoningEffort;
    private final String searchContextSize;

    DeepResearchProfile(
            String key,
            String label,
            String model,
            String description,
            Duration timeout,
            int maxToolCalls,
            int maxOutputTokens,
            String reasoningEffort,
            String searchContextSize
    ) {
        this.key = key;
        this.label = label;
        this.model = model;
        this.description = description;
        this.timeout = timeout;
        this.maxToolCalls = maxToolCalls;
        this.maxOutputTokens = maxOutputTokens;
        this.reasoningEffort = reasoningEffort;
        this.searchContextSize = searchContextSize;
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

    public int maxToolCalls() {
        return maxToolCalls;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public String reasoningEffort() {
        return reasoningEffort;
    }

    public String searchContextSize() {
        return searchContextSize;
    }

    public static DeepResearchProfile fromKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(profile -> profile.key.equals(normalized))
                .findFirst()
                .orElse(null);
    }

    public static List<DeepResearchProfile> all() {
        return List.of(values());
    }
}
