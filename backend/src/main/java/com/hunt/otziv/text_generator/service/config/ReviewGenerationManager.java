package com.hunt.otziv.text_generator.service.config;

import com.hunt.otziv.text_generator.dto.PromptDTO;
import com.hunt.otziv.text_generator.service.toGPT.ReviewGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewGenerationManager {

    private final ReviewGeneratorService reviewGeneratorService;

    @Async("reviewExecutor")
    public CompletableFuture<String> generateReviewAsync(PromptDTO promptDTO) {
        try {
            String result = reviewGeneratorService.safeGenerateSingleReview(promptDTO);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("❌ Ошибка генерации в async: {}", e.getMessage());
            return CompletableFuture.completedFuture("⚠️ Ошибка генерации");
        }
    }
}
