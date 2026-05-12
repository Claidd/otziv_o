package com.hunt.otziv.reputationai.domain;

public record CompanySource(
        String type,
        String title,
        String url,
        String excerpt
) {
    public CompanySource {
        type = normalize(type, "unknown");
        title = normalize(title, "");
        url = normalize(url, "");
        excerpt = normalize(excerpt, "");
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        return value.trim();
    }
}
