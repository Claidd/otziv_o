package com.hunt.otziv.r_review.bot;

import com.hunt.otziv.p_products.services.service.BotAssignmentService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewWalkReadinessReconciliationJobTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private BotAssignmentService botAssignmentService;

    @Test
    void reconcilesAllUnpublishedReviewsWithAssignedAccounts() {
        Review review = new Review();
        List<Review> reviews = List.of(review);
        when(reviewRepository.findByPublishFalseAndBotIsNotNull()).thenReturn(reviews);

        new ReviewWalkReadinessReconciliationJob(reviewRepository, botAssignmentService).reconcile();

        verify(botAssignmentService).promoteReviewsWithWalkedAccounts(reviews);
    }
}
