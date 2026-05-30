package com.hunt.otziv.r_review.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewTextPolicyTest {

    @Test
    void blankOrPlaceholderIncludesPlaceholderWithOperatorNotes() {
        assertTrue(ReviewTextPolicy.isBlankOrPlaceholder("текст отзыва"));
        assertTrue(ReviewTextPolicy.isBlankOrPlaceholder("  текст отзыва Нужно подсавить текст"));
        assertTrue(ReviewTextPolicy.isBlankOrPlaceholder("Нужно подставить текст"));
        assertTrue(ReviewTextPolicy.isBlankOrPlaceholder("подсавить текст"));
    }

    @Test
    void blankOrPlaceholderKeepsRealReviewText() {
        assertFalse(ReviewTextPolicy.isBlankOrPlaceholder("Качественные веники, беру не первый раз."));
    }
}
