package com.hunt.otziv.reputationai.infrastructure.search.dto;

public record SearchQuery(
        String text,
        int limit
) {
    public SearchQuery {
        text = text == null ? "" : text.trim();
        limit = Math.max(1, Math.min(20, limit));
    }
}
