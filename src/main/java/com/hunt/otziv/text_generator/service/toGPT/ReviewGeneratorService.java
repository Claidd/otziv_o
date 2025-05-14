package com.hunt.otziv.text_generator.service.toGPT;

import com.hunt.otziv.text_generator.dto.PromptDTO;

import java.util.List;

public interface ReviewGeneratorService {
    String generateReview(String category, String tone, String url);

    List<String> generateMultipleReviews(String category, String tone, String site, int count);

    String safeAnalyzeSiteText(String siteRaw);

    String safeGenerateSingleReview(PromptDTO promptDTO);

    String minusSlova(String text);

    List<String> diversifyReviews(List<String> rawReviews);
    String safeAnalyzeSiteTextNoShablon(String siteRaw);
}
