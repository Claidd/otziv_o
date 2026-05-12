package com.hunt.otziv.reputationai.application;

import java.util.List;

public record PageRoleContext(
        String companyName,
        String city,
        String officialWebsite,
        String category,
        String subCategory
) {
    public PageRoleContext {
        companyName = normalize(companyName);
        city = normalize(city);
        officialWebsite = normalize(officialWebsite);
        category = normalize(category);
        subCategory = normalize(subCategory);
    }

    public List<String> businessTokens() {
        return List.of(category, subCategory, "отзыв", "рейтинг", "адрес", "телефон")
                .stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
