package com.hunt.otziv.reputationai.infrastructure.web;

public record CrawledPage(
        String url,
        String title,
        String text
) {
    public CrawledPage {
        url = normalize(url);
        title = normalize(title);
        text = normalize(text);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
