package com.hunt.otziv.reputationai.infrastructure.ai.dto;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

public record AiRequest(
        String task,
        String systemPrompt,
        String userPrompt,
        Double temperature,
        Boolean jsonObject,
        Integer maxTokens,
        Duration timeout,
        Boolean thinkingEnabled,
        String reasoningEffort
) {
    public AiRequest(
            String task,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            Boolean jsonObject,
            Integer maxTokens,
            Duration timeout,
            Boolean thinkingEnabled
    ) {
        this(task, systemPrompt, userPrompt, temperature, jsonObject, maxTokens, timeout, thinkingEnabled, null);
    }

    public AiRequest(
            String task,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            Boolean jsonObject,
            Integer maxTokens,
            Duration timeout
    ) {
        this(task, systemPrompt, userPrompt, temperature, jsonObject, maxTokens, timeout, null);
    }

    public AiRequest(String task, String systemPrompt, String userPrompt, Double temperature) {
        this(task, systemPrompt, userPrompt, temperature, false);
    }

    public AiRequest(String task, String systemPrompt, String userPrompt, Double temperature, Boolean jsonObject) {
        this(task, systemPrompt, userPrompt, temperature, jsonObject, null, null, null);
    }

    public AiRequest {
        task = task == null ? "text" : task.trim();
        systemPrompt = systemPrompt == null ? "" : systemPrompt.trim();
        userPrompt = userPrompt == null ? "" : userPrompt.trim();
        temperature = temperature == null ? 0.4 : temperature;
        jsonObject = jsonObject != null && jsonObject;
        maxTokens = maxTokens == null ? null : Math.max(1, maxTokens);
        timeout = timeout == null ? null : timeout;
        thinkingEnabled = thinkingEnabled == null ? null : thinkingEnabled;
        if (reasoningEffort != null) {
            reasoningEffort = reasoningEffort.trim().toLowerCase(Locale.ROOT);
            if (!Set.of("low", "high", "max").contains(reasoningEffort)) {
                throw new IllegalArgumentException("reasoningEffort must be low, high or max");
            }
        }
    }
}
