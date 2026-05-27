package com.hunt.otziv.r_review.services;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchive;
import com.hunt.otziv.r_review.model.ReviewArchiveSourceReason;
import com.hunt.otziv.r_review.repository.ReviewArchiveRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewArchiveServiceImplTest {

    @Mock
    private ReviewArchiveRepository reviewArchiveRepository;

    @Mock
    private ReviewService reviewService;

    @InjectMocks
    private ReviewArchiveServiceImpl reviewArchiveService;

    @Test
    void saveNewReviewArchiveSkipsBlankText() {
        Review review = review(10L, " ");
        when(reviewService.getReviewById(10L)).thenReturn(review);

        reviewArchiveService.saveNewReviewArchive(10L);

        verify(reviewArchiveRepository, never()).findFirstByText(org.mockito.ArgumentMatchers.any());
        verify(reviewArchiveRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void saveNewReviewArchiveSkipsPlaceholderTextIgnoringCase() {
        Review review = review(11L, "  текст отзыва  ");
        when(reviewService.getReviewById(11L)).thenReturn(review);

        reviewArchiveService.saveNewReviewArchive(11L);

        verify(reviewArchiveRepository, never()).findFirstByText(org.mockito.ArgumentMatchers.any());
        verify(reviewArchiveRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void saveNewReviewArchiveSavesValidTextWhenMissing() {
        Review review = review(12L, "Готовый текст");
        review.setAnswer("Ответ");
        Order order = new Order();
        order.setId(100L);
        OrderDetails details = new OrderDetails();
        details.setOrder(order);
        review.setOrderDetails(details);
        when(reviewService.getReviewById(12L)).thenReturn(review);
        when(reviewArchiveRepository.findFirstByText("Готовый текст")).thenReturn(Optional.empty());

        reviewArchiveService.saveNewReviewArchive(12L, ReviewArchiveSourceReason.PUBLISHED);

        ArgumentCaptor<ReviewArchive> captor = ArgumentCaptor.forClass(ReviewArchive.class);
        verify(reviewArchiveRepository).save(captor.capture());
        assertEquals("Готовый текст", captor.getValue().getText());
        assertEquals("Ответ", captor.getValue().getAnswer());
        assertSame(review, captor.getValue().getSourceReview());
        assertSame(order, captor.getValue().getSourceOrder());
        assertEquals(ReviewArchiveSourceReason.PUBLISHED, captor.getValue().getSourceReason());
    }

    @Test
    void saveNewReviewArchiveEnrichesExistingArchiveWithMissingSource() {
        Review review = review(13L, "Готовый текст");
        ReviewArchive existingArchive = new ReviewArchive();
        existingArchive.setText("Готовый текст");
        when(reviewService.getReviewById(13L)).thenReturn(review);
        when(reviewArchiveRepository.findFirstByText("Готовый текст")).thenReturn(Optional.of(existingArchive));

        reviewArchiveService.saveNewReviewArchive(13L, ReviewArchiveSourceReason.ORDER_ARCHIVED);

        assertSame(review, existingArchive.getSourceReview());
        assertEquals(ReviewArchiveSourceReason.ORDER_ARCHIVED, existingArchive.getSourceReason());
        verify(reviewArchiveRepository).save(existingArchive);
    }

    @Test
    void saveNewReviewArchiveUpgradesExistingBackfillArchiveWhenPublished() {
        Review review = review(14L, "Готовый текст");
        ReviewArchive existingArchive = new ReviewArchive();
        existingArchive.setText("Готовый текст");
        existingArchive.setSourceReason(ReviewArchiveSourceReason.BACKFILL);
        when(reviewService.getReviewById(14L)).thenReturn(review);
        when(reviewArchiveRepository.findFirstByText("Готовый текст")).thenReturn(Optional.of(existingArchive));

        reviewArchiveService.saveNewReviewArchive(14L, ReviewArchiveSourceReason.PUBLISHED);

        assertSame(review, existingArchive.getSourceReview());
        assertEquals(ReviewArchiveSourceReason.PUBLISHED, existingArchive.getSourceReason());
        verify(reviewArchiveRepository).save(existingArchive);
    }

    @Test
    void existsByTextExcludingOwnSourceSkipsBlankText() {
        boolean exists = reviewArchiveService.existsByTextExcludingOwnSource(" ", 1L, 2L);

        assertEquals(false, exists);
        verify(reviewArchiveRepository, never()).existsByTextExcludingOwnSource(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private Review review(Long id, String text) {
        Review review = new Review();
        review.setId(id);
        review.setText(text);
        return review;
    }
}
