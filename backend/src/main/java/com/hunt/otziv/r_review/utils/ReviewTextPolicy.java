package com.hunt.otziv.r_review.utils;

import java.util.List;
import java.util.Locale;

public final class ReviewTextPolicy {

    private static final String PLACEHOLDER_TEXT = "текст отзыва";
    private static final List<String> PLACEHOLDER_PREFIXES = List.of(
            PLACEHOLDER_TEXT,
            "нужно подставить",
            "нужно подсавить",
            "подставить текст",
            "подсавить текст"
    );
    private static final int SHORT_COMMON_REVIEW_MAX_CHARS = 100;
    private static final int SHORT_COMMON_REVIEW_MAX_WORDS = 12;

    private ReviewTextPolicy() {
    }

    public static boolean isBlankOrPlaceholder(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }

        String normalized = text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return PLACEHOLDER_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    public static boolean isShortCommonReviewText(String text) {
        if (isBlankOrPlaceholder(text)) {
            return false;
        }

        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.length() <= SHORT_COMMON_REVIEW_MAX_CHARS
                || normalized.split("\\s+").length <= SHORT_COMMON_REVIEW_MAX_WORDS;
    }
}
