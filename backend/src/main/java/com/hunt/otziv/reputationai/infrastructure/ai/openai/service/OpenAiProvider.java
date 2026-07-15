package com.hunt.otziv.reputationai.infrastructure.ai.openai.service;

import com.hunt.otziv.reputationai.infrastructure.ai.service.AiProvider;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.dto.OpenAiResponseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OpenAiProvider implements AiProvider {

    private final OpenAiResponsesClient responsesClient;

    @Override
    public AiResponse generate(AiRequest request) {
        if (!isAvailable()) {
            return new AiResponse("", responsesClient.activeProviderName(), 0, 0);
        }

        OpenAiResponseResult result = responsesClient.createTextResponse(request);
        return new AiResponse(
                result.text(),
                responsesClient.activeProviderName(),
                result.inputTokens(),
                result.outputTokens(),
                result.errorMessage()
        );
    }

    public AiResponse generateContentPack(AiRequest request, String profileKey) {
        if (!isAvailable()) {
            return new AiResponse("", responsesClient.activeProviderName(), 0, 0);
        }

        OpenAiResponseResult result = responsesClient.createContentPackResponse(request, profileKey);
        return new AiResponse(
                result.text(),
                responsesClient.activeProviderName(),
                result.inputTokens(),
                result.outputTokens(),
                result.errorMessage()
        );
    }

    public AiResponse generateReviewTemplates(AiRequest request, String profileKey) {
        if (!isAvailable()) {
            return new AiResponse("", responsesClient.activeProviderName(), 0, 0);
        }

        OpenAiResponseResult result = responsesClient.createReviewTemplatesResponse(request, profileKey);
        return new AiResponse(
                result.text(),
                responsesClient.activeProviderName(),
                result.inputTokens(),
                result.outputTokens(),
                result.errorMessage()
        );
    }

    public AiResponse generateSingleReviewDraft(AiRequest request, String profileKey) {
        if (!isAvailable()) {
            return new AiResponse("", responsesClient.activeProviderName(), 0, 0);
        }

        OpenAiResponseResult result = responsesClient.createSingleReviewDraftResponse(request, profileKey);
        return new AiResponse(
                result.text(),
                responsesClient.activeProviderName(),
                result.inputTokens(),
                result.outputTokens(),
                result.errorMessage()
        );
    }

    public AiResponse generateBatchReviewDraft(AiRequest request, String profileKey) {
        if (!isAvailable()) {
            return new AiResponse("", responsesClient.activeProviderName(), 0, 0);
        }

        OpenAiResponseResult result = responsesClient.createBatchReviewDraftResponse(request, profileKey);
        return new AiResponse(
                result.text(),
                responsesClient.activeProviderName(),
                result.inputTokens(),
                result.outputTokens(),
                result.errorMessage()
        );
    }

    public AiResponse generateBatchReviewWritingGuide(AiRequest request, String profileKey) {
        if (!isAvailable()) {
            return new AiResponse("", responsesClient.activeProviderName(), 0, 0);
        }

        OpenAiResponseResult result = responsesClient.createBatchReviewWritingGuideResponse(request, profileKey);
        return new AiResponse(
                result.text(),
                responsesClient.activeProviderName(),
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
