package com.hunt.otziv.r_review.utils;

public final class ReviewTextPolicy {

    private static final String PLACEHOLDER_TEXT = "текст отзыва";
    private static final int SHORT_COMMON_REVIEW_MAX_CHARS = 100;
    private static final int SHORT_COMMON_REVIEW_MAX_WORDS = 12;

    private ReviewTextPolicy() {
    }

    public static boolean isBlankOrPlaceholder(String text) {
        return text == null || text.isBlank() || PLACEHOLDER_TEXT.equalsIgnoreCase(text.trim());
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
