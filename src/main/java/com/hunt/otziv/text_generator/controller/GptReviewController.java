package com.hunt.otziv.text_generator.controller;

import com.hunt.otziv.text_generator.service.ReviewGeneratorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/review")
public class GptReviewController {

    private final ReviewGeneratorService reviewService;

    public GptReviewController(ReviewGeneratorService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public ResponseEntity<String> generate(
            @RequestParam String product,
            @RequestParam String category,
            @RequestParam(defaultValue = "позитивный") String tone) {
        long startTime = System.nanoTime();
        String review = reviewService.generateReview(product, category, tone);
        checkTimeMethod("Время создания отзыва: ", startTime);
        return ResponseEntity.ok(review);
    }

    private void checkTimeMethod(String text, long startTime){
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        log.info(text + "%.4f сек%n", timeElapsed);
    }
}
