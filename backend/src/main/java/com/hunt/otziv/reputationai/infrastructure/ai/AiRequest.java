package com.hunt.otziv.reputationai.infrastructure.ai;

import java.time.Duration;

public record AiRequest(
        String task,
        String systemPrompt,
        String userPrompt,
        Double temperature,
        Boolean jsonObject,
        Integer maxTokens,
        Duration timeout
) {
    public AiRequest(String task, String systemPrompt, String userPrompt, Double temperature) {
        this(task, systemPrompt, userPrompt, temperature, false);
    }

    public AiRequest(String task, String systemPrompt, String userPrompt, Double temperature, Boolean jsonObject) {
        this(task, systemPrompt, userPrompt, temperature, jsonObject, null, null);
    }

    public AiRequest {
        task = task == null ? "text" : task.trim();
        systemPrompt = systemPrompt == null ? "" : systemPrompt.trim();
        userPrompt = userPrompt == null ? "" : userPrompt.trim();
        temperature = temperature == null ? 0.4 : temperature;
        jsonObject = jsonObject != null && jsonObject;
        maxTokens = maxTokens == null ? null : Math.max(1, maxTokens);
        timeout = timeout == null ? null : timeout;
    }
}
