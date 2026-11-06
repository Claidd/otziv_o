package com.hunt.otziv.text_generator.service.gpt;

import com.hunt.otziv.text_generator.dto.PromptDTO;
import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewGeneratorServiceImpl implements ReviewGeneratorService {
    private final OpenAIClient openAIClient;

    private String createChatCompletion(String model, String systemMessage, String userMessage, double temperature, long maxTokens) {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(model)
                .addSystemMessage(systemMessage)
                .addUserMessage(userMessage)
                .temperature(temperature)
                .maxCompletionTokens(maxTokens)
                .build();

        ChatCompletion result = openAIClient.chat().completions().create(params);
        return result.choices().getFirst().message().content().orElse("").trim();
    }


    public String safeGenerateSingleReview(PromptDTO promptDTO) {
        try {
            String content = createChatCompletion(
                    "gpt-4o",
                    "Ты человек пишуший отзыв о компании в которой был. " + promptDTO.getSystem(),
                    promptDTO.getPrompt(),
                    promptDTO.getTemperature(),
                    400
            );
            log.info("✅ Получен отзыв ({} токенов)", content.length());
            return content;
        } catch (Exception e) {
            log.error("❌ Ошибка при генерации отзыва: {}", e.getMessage());
            return "⚠️ Ошибка при генерации отзыва.";
        }
    }









































}
