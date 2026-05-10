package com.hunt.otziv.r_review.utils;

public final class ReviewTextPolicy {

    private static final String PLACEHOLDER_TEXT = "текст отзыва";

    private ReviewTextPolicy() {
    }

    public static boolean isBlankOrPlaceholder(String text) {
        return text == null || text.isBlank() || PLACEHOLDER_TEXT.equalsIgnoreCase(text.trim());
    }
}
