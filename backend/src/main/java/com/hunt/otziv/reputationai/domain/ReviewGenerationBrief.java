package com.hunt.otziv.reputationai.domain;

import java.util.List;

public record ReviewGenerationBrief(
        String company,
        String city,
        String category,
        String businessType,
        List<String> services,
        List<String> products,
        List<String> prices,
        List<String> advantages,
        List<String> reviewIdeas,
        List<String> travelFromCenter,
        List<String> employees,
        List<String> amenities,
        List<String> parking,
        List<String> interestingFacts,
        List<String> allowedScenarioTypes
) {
    public ReviewGenerationBrief {
        company = company == null ? "" : company.trim();
        city = city == null ? "" : city.trim();
        category = category == null ? "" : category.trim();
        businessType = businessType == null || businessType.isBlank() ? "other" : businessType.trim();
        services = clean(services, 18);
        products = clean(products, 18);
        prices = clean(prices, 12);
        advantages = clean(advantages, 18);
        reviewIdeas = clean(reviewIdeas, 30);
        travelFromCenter = clean(travelFromCenter, 8);
        employees = clean(employees, 10);
        amenities = clean(amenities, 12);
        parking = clean(parking, 8);
        interestingFacts = clean(interestingFacts, 16);
        allowedScenarioTypes = clean(allowedScenarioTypes, 14);
    }

    private static List<String> clean(List<String> values, int limit) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(limit)
                .toList();
    }
}
