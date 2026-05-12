package com.hunt.otziv.reputationai.infrastructure.ai;

public record AiResponse(
        String text,
        String provider,
        Integer inputTokens,
        Integer outputTokens
) {
    public AiResponse {
        text = text == null ? "" : text.trim();
        provider = provider == null ? "unknown" : provider.trim();
        inputTokens = inputTokens == null ? 0 : inputTokens;
        outputTokens = outputTokens == null ? 0 : outputTokens;
    }
}
