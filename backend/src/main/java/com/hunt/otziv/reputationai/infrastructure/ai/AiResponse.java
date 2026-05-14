package com.hunt.otziv.reputationai.infrastructure.ai;

public record AiResponse(
        String text,
        String provider,
        Integer inputTokens,
        Integer outputTokens,
        String errorMessage
) {
    public AiResponse(String text, String provider, Integer inputTokens, Integer outputTokens) {
        this(text, provider, inputTokens, outputTokens, "");
    }

    public AiResponse {
        text = text == null ? "" : text.trim();
        provider = provider == null ? "unknown" : provider.trim();
        inputTokens = inputTokens == null ? 0 : inputTokens;
        outputTokens = outputTokens == null ? 0 : outputTokens;
        errorMessage = errorMessage == null ? "" : errorMessage.trim();
    }
}
