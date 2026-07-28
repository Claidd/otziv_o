package com.hunt.otziv.r_review.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.lang.annotation.Annotation;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ReviewTextReadyTrackingTest {

    @Test
    void marksOnlyRealPlaceholderToReadyTransition() {
        Review review = new Review();
        review.setText("текст отзыва");
        review.rememberTextReadiness();

        review.setText("Готовый текст карточки");
        review.markTextReadyTransition();

        assertNotNull(review.getTextReadyAt());
    }

    @Test
    void legacyReadyTextEditDoesNotCreateFalseCompletion() {
        Review review = new Review();
        review.setText("Исторический готовый текст");
        review.rememberTextReadiness();

        review.setText("Исторический текст с исправлением");
        review.markTextReadyTransition();

        assertNull(review.getTextReadyAt());
    }

    @Test
    void newlyCreatedReadyCardGetsOneShotTimestamp() {
        Review review = new Review();
        review.setText("Готовый текст новой карточки");

        review.markInitialTextReadyAt();

        assertNotNull(review.getTextReadyAt());
    }

    @Test
    void initialVigulStateGetsTimestampWithoutOverwritingImportedTimestamp() {
        Review newReview = new Review();
        newReview.setVigul(false);
        newReview.markInitialVigulState();

        assertNotNull(newReview.getVigulChangedAt());

        LocalDateTime importedAt = LocalDateTime.of(2025, 4, 3, 12, 15);
        Review importedReview = new Review();
        importedReview.setVigul(true);
        importedReview.setVigulChangedAt(importedAt);
        importedReview.markInitialVigulState();

        assertEquals(importedAt, importedReview.getVigulChangedAt());
    }

    @Test
    void vigulTransitionIsStampedOncePerActualStateChange() {
        LocalDateTime historical = LocalDateTime.of(2025, 4, 3, 12, 15);
        Review review = new Review();
        review.setVigul(false);
        review.setVigulChangedAt(historical);
        review.rememberVigulState();

        review.setVigul(true);
        review.markVigulTransition();

        LocalDateTime firstTransition = review.getVigulChangedAt();
        assertTrue(firstTransition.isAfter(historical));

        review.rememberUpdatedTrackedState();
        review.markVigulTransition();

        assertEquals(firstTransition, review.getVigulChangedAt());
    }

    @Test
    void returningToNagulIsAlsoAnExactStateTransition() {
        LocalDateTime historical = LocalDateTime.of(2025, 4, 3, 12, 15);
        Review review = new Review();
        review.setVigul(true);
        review.setVigulChangedAt(historical);
        review.rememberVigulState();

        review.setVigul(false);
        review.markVigulTransition();

        assertTrue(review.getVigulChangedAt().isAfter(historical));
    }

    @Test
    void usesOneJpaCallbackPerLifecycleEvent() {
        assertEquals(1, callbackCount(PostLoad.class));
        assertEquals(1, callbackCount(PrePersist.class));
        assertEquals(1, callbackCount(PreUpdate.class));
    }

    private long callbackCount(Class<? extends Annotation> annotation) {
        return Arrays.stream(Review.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(annotation))
                .count();
    }
}
