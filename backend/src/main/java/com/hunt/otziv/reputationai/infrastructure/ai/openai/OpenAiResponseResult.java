package com.hunt.otziv.reputationai.infrastructure.ai.openai;

public record OpenAiResponseResult(
        String responseId,
        String text,
        String provider,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        String errorMessage
) {
    public OpenAiResponseResult(String responseId, String text, String model, Integer inputTokens, Integer outputTokens) {
        this(responseId, text, "openai", model, inputTokens, outputTokens, "");
    }

    public OpenAiResponseResult(String responseId, String text, String model, Integer inputTokens, Integer outputTokens, String errorMessage) {
        this(responseId, text, "openai", model, inputTokens, outputTokens, errorMessage);
    }

    public OpenAiResponseResult(String responseId, String text, String provider, String model, Integer inputTokens, Integer outputTokens) {
        this(responseId, text, provider, model, inputTokens, outputTokens, "");
    }

    public OpenAiResponseResult {
        responseId = responseId == null ? "" : responseId.trim();
        text = text == null ? "" : text.trim();
        provider = provider == null || provider.isBlank() ? "openai" : provider.trim();
        model = model == null ? "" : model.trim();
        inputTokens = inputTokens == null ? 0 : inputTokens;
        outputTokens = outputTokens == null ? 0 : outputTokens;
        errorMessage = errorMessage == null ? "" : errorMessage.trim();
    }
}
