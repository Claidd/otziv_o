package com.hunt.otziv.reputationai.domain;

import java.time.LocalDateTime;
import java.util.List;

public record ResearchSnapshot(
        Long companyId,
        String companyName,
        String city,
        String website,
        String category,
        String subCategory,
        String companyNotes,
        List<String> products,
        List<String> advantages,
        List<String> commonPositiveTopics,
        List<String> commonNegativeTopics,
        List<CompanyResearchAnswer> researchAnswers,
        List<CompanySource> sources,
        String searchProvider,
        boolean searchAvailable,
        List<String> searchQueries,
        int searchResultsCount,
        int websitePagesRead,
        List<String> warnings,
        LocalDateTime createdAt
) {
    public ResearchSnapshot {
        companyName = normalize(companyName);
        city = normalize(city);
        website = normalize(website);
        category = normalize(category);
        subCategory = normalize(subCategory);
        companyNotes = normalize(companyNotes);
        products = safeList(products);
        advantages = safeList(advantages);
        commonPositiveTopics = safeList(commonPositiveTopics);
        commonNegativeTopics = safeList(commonNegativeTopics);
        researchAnswers = researchAnswers == null ? List.of() : List.copyOf(researchAnswers);
        sources = sources == null ? List.of() : List.copyOf(sources);
        searchProvider = normalize(searchProvider);
        searchQueries = safeList(searchQueries);
        searchResultsCount = Math.max(0, searchResultsCount);
        websitePagesRead = Math.max(0, websitePagesRead);
        warnings = safeList(warnings);
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> safeList(List<String> values) {
        if (values == null) {
            return List.of();
        }

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
