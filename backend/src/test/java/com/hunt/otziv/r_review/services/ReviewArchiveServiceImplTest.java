package com.hunt.otziv.r_review.services;

import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchive;
import com.hunt.otziv.r_review.repository.ReviewArchiveRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        verify(reviewArchiveRepository, never()).existsByText(org.mockito.ArgumentMatchers.any());
        verify(reviewArchiveRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void saveNewReviewArchiveSkipsPlaceholderTextIgnoringCase() {
        Review review = review(11L, "  текст отзыва  ");
        when(reviewService.getReviewById(11L)).thenReturn(review);

        reviewArchiveService.saveNewReviewArchive(11L);

        verify(reviewArchiveRepository, never()).existsByText(org.mockito.ArgumentMatchers.any());
        verify(reviewArchiveRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void saveNewReviewArchiveSavesValidTextWhenMissing() {
        Review review = review(12L, "Готовый текст");
        review.setAnswer("Ответ");
        when(reviewService.getReviewById(12L)).thenReturn(review);
        when(reviewArchiveRepository.existsByText("Готовый текст")).thenReturn(false);

        reviewArchiveService.saveNewReviewArchive(12L);

        ArgumentCaptor<ReviewArchive> captor = ArgumentCaptor.forClass(ReviewArchive.class);
        verify(reviewArchiveRepository).save(captor.capture());
        assertEquals("Готовый текст", captor.getValue().getText());
        assertEquals("Ответ", captor.getValue().getAnswer());
    }

    private Review review(Long id, String text) {
        Review review = new Review();
        review.setId(id);
        review.setText(text);
        return review;
    }
}
