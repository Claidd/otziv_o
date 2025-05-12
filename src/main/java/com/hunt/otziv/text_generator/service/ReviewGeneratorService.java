package com.hunt.otziv.text_generator.service;

import java.util.List;

public interface ReviewGeneratorService {
    String generateReview(String category, String tone, String url);

    List<String> generateMultipleReviews(String category, String tone, String site, int count);

    String safeAnalyzeSiteText(String siteRaw);

    String safeGenerateSingleReview(String prompt);
}
