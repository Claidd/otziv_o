package com.hunt.otziv.r_review.bot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.r_review.bot.repository.ReviewBotAssignmentExclusionRepository;
import com.hunt.otziv.r_review.model.Review;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewBotAssignmentExclusionServiceTest {

    @Mock
    private ReviewBotAssignmentExclusionRepository repository;

    @Test
    void returnsDefensiveCopyOfRejectedBotIds() {
        Review review = new Review();
        review.setId(15L);
        Set<Long> stored = Set.of(6L, 7L);
        when(repository.findBotIdsByReviewId(15L)).thenReturn(stored);

        Set<Long> result = service().excludedBotIds(review);

        assertEquals(stored, result);
        assertNotSame(stored, result);
    }

    @Test
    void storesCurrentBotWithIdempotentInsert() {
        Review review = new Review();
        review.setId(15L);
        Bot bot = new Bot();
        bot.setId(6L);
        review.setBot(bot);

        service().rejectCurrentBot(review, "change");

        verify(repository).insertIgnore(15L, 6L, "CHANGE");
    }

    @Test
    void clearsOneReviewAndOldPublishedReviews() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 6, 3, 35);
        when(repository.deleteByReviewId(15L)).thenReturn(3);
        when(repository.deletePublishedBefore(cutoff)).thenReturn(7);

        ReviewBotAssignmentExclusionService service = service();

        assertEquals(3, service.clearForReview(15L));
        assertEquals(7, service.clearPublishedBefore(cutoff));
    }

    private ReviewBotAssignmentExclusionService service() {
        return new ReviewBotAssignmentExclusionService(repository);
    }
}
