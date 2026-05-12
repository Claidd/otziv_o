package com.hunt.otziv.reputationai.infrastructure.ai;

public record AiRequest(
        String task,
        String systemPrompt,
        String userPrompt,
        Double temperature,
        Boolean jsonObject
) {
    public AiRequest(String task, String systemPrompt, String userPrompt, Double temperature) {
        this(task, systemPrompt, userPrompt, temperature, false);
    }

    public AiRequest {
        task = task == null ? "text" : task.trim();
        systemPrompt = systemPrompt == null ? "" : systemPrompt.trim();
        userPrompt = userPrompt == null ? "" : userPrompt.trim();
        temperature = temperature == null ? 0.4 : temperature;
        jsonObject = jsonObject != null && jsonObject;
    }
}
