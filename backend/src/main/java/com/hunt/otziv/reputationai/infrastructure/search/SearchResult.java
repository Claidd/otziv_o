package com.hunt.otziv.reputationai.infrastructure.search;

public record SearchResult(
        String title,
        String url,
        String snippet,
        String provider
) {
    public SearchResult {
        title = normalize(title);
        url = normalize(url);
        snippet = normalize(snippet);
        provider = normalize(provider);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
