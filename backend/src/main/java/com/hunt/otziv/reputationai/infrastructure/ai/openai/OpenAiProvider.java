package com.hunt.otziv.reputationai.infrastructure.ai.openai;

import com.hunt.otziv.reputationai.infrastructure.ai.AiProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.AiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OpenAiProvider implements AiProvider {

    private final OpenAiResponsesClient responsesClient;

    @Override
    public AiResponse generate(AiRequest request) {
        if (!isAvailable()) {
            return new AiResponse("", providerName(), 0, 0);
        }

        OpenAiResponseResult result = responsesClient.createTextResponse(request);
        return new AiResponse(
                result.text(),
                providerName(),
                result.inputTokens(),
                result.outputTokens(),
                result.errorMessage()
        );
    }

    public AiResponse generateContentPack(AiRequest request, String profileKey) {
        if (!isAvailable()) {
            return new AiResponse("", providerName(), 0, 0);
        }

        OpenAiResponseResult result = responsesClient.createContentPackResponse(request, profileKey);
        return new AiResponse(
                result.text(),
                providerName(),
                result.inputTokens(),
                result.outputTokens(),
                result.errorMessage()
        );
    }

    public AiResponse generateReviewTemplates(AiRequest request, String profileKey) {
        if (!isAvailable()) {
            return new AiResponse("", providerName(), 0, 0);
        }

        OpenAiResponseResult result = responsesClient.createReviewTemplatesResponse(request, profileKey);
        return new AiResponse(
                result.text(),
                providerName(),
                result.inputTokens(),
                result.outputTokens(),
                result.errorMessage()
        );
    }

    public AiResponse generateSingleReviewDraft(AiRequest request, String profileKey) {
        if (!isAvailable()) {
            return new AiResponse("", providerName(), 0, 0);
        }

        OpenAiResponseResult result = responsesClient.createSingleReviewDraftResponse(request, profileKey);
        return new AiResponse(
                result.text(),
                providerName(),
                result.inputTokens(),
                result.outputTokens(),
                result.errorMessage()
        );
    }

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public boolean isAvailable() {
        return responsesClient.isAvailable();
    }
}
