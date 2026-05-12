package com.hunt.otziv.reputationai.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

public enum DeepResearchProfile {
    ECONOMY(
            "economy",
            "Эконом",
            "gpt-5.4-mini",
            "Быстрее и дешевле для локальных проверок.",
            Duration.ofMinutes(5),
            6,
            6000,
            "low",
            "low"
    ),
    QUALITY(
            "quality",
            "Качество",
            "gpt-5.5",
            "Основной режим для хорошего отчёта.",
            Duration.ofMinutes(8),
            16,
            12000,
            "low",
            "low"
    ),
    MAXIMUM(
            "maximum",
            "Максимум",
            "gpt-5.5",
            "Устойчивый режим 5.5 с усиленным reasoning без чрезмерного расхода TPM.",
            Duration.ofMinutes(12),
            20,
            14000,
            "medium",
            "low"
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
