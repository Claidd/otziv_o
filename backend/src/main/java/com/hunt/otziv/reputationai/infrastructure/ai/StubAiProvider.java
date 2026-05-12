package com.hunt.otziv.reputationai.infrastructure.ai;

import org.springframework.stereotype.Service;

@Service
public class StubAiProvider implements AiProvider {

    @Override
    public AiResponse generate(AiRequest request) {
        String text = switch (request.task()) {
            case "company-description" -> "Черновое описание подготовлено локальным AI-провайдером. Подключите GigaChat или YandexGPT для полноценной генерации.";
            case "review-draft" -> "Черновик отзыва подготовлен только как подсказка. Перед публикацией клиент должен отредактировать его под свой реальный опыт.";
            default -> "Локальный AI-провайдер готов принять задачу: " + request.task();
        };

        return new AiResponse(text, providerName(), estimateTokens(request.userPrompt()), estimateTokens(text));
    }

    @Override
    public String providerName() {
        return "local";
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        return Math.max(1, text.length() / 4);
    }
}
