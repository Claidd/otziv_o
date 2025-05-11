package com.hunt.otziv.text_generator.service;

import com.theokanning.openai.OpenAiHttpException;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewGeneratorServiceImpl implements ReviewGeneratorService {
    private final OpenAiService openAiService;


    public String generateReview(String product, String category, String tone) {
        String prompt = String.format("""
        Напиши живой, натуральный отзыв на товар или услугу.
        Название: %s
        Категория: %s
        Тональность: %s

        Укажи, зачем человек купил, что понравилось, и общее впечатление.
        Объём: 2–3 абзаца.
        """, product, category, tone);

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("gpt-4o") // или "gpt-3.5-turbo"
                .messages(List.of(
                        new ChatMessage("system", "Ты — профессиональный копирайтер."),
                        new ChatMessage("user", prompt)
                ))
                .temperature(0.8)
                .maxTokens(300)
                .build();

        try {
            ChatCompletionResult result = openAiService.createChatCompletion(request);
            return result.getChoices().getFirst().getMessage().getContent();
        } catch (OpenAiHttpException e) {
            log.error("Ошибка OpenAI: {}", e.getMessage());
            return "⚠️ Ошибка при генерации отзыва: лимит API исчерпан или временно недоступен.";
        } catch (Exception e) {
            log.error("Неизвестная ошибка при обращении к OpenAI", e);
            return "⚠️ Неизвестная ошибка при генерации отзыва.";
        }
    }

}
