package com.hunt.otziv.reputationai.infrastructure.ai.deepseek.dto;

import java.util.List;

public record DeepSeekAnthropicResult(
        String responseId,
        String text,
        int inputTokens,
        int outputTokens,
        int webSearchRequests,
        int webSources,
        List<String> webSourceUrls,
        String errorMessage
) {
    public DeepSeekAnthropicResult {
        responseId = responseId == null ? "" : responseId.trim();
        text = text == null ? "" : text.trim();
        inputTokens = Math.max(0, inputTokens);
        outputTokens = Math.max(0, outputTokens);
        webSearchRequests = Math.max(0, webSearchRequests);
        webSources = Math.max(0, webSources);
        webSourceUrls = webSourceUrls == null
                ? List.of()
                : webSourceUrls.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        errorMessage = errorMessage == null ? "" : errorMessage.trim();
    }
}
