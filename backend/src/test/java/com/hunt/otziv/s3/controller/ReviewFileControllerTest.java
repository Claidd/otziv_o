package com.hunt.otziv.s3.controller;

import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.s3.service.S3UploadService;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewFileControllerTest {

    @Test
    void failedDatabaseSaveDeletesOnlyNewlyUploadedObject() {
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        S3UploadService s3UploadService = mock(S3UploadService.class);
        MultipartFile file = mock(MultipartFile.class);
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        Review review = Review.builder().id(17L).url("https://cdn.test/reviews/17-old.jpg").build();
        when(reviewRepository.findById(17L)).thenReturn(Optional.of(review));
        when(s3UploadService.uploadFile(file, "reviews", review.getUrl(), 17L))
                .thenReturn("https://cdn.test/reviews/17-new.jpg");
        when(reviewRepository.save(review)).thenThrow(new IllegalStateException("db failed"));

        ReviewFileController controller = new ReviewFileController(reviewRepository, s3UploadService);

        assertThrows(
                IllegalStateException.class,
                () -> controller.uploadPhoto(17L, file, redirectAttributes)
        );

        verify(s3UploadService).deleteFileAfterCommit(
                "https://cdn.test/reviews/17-new.jpg",
                "reviews",
                17L
        );
        verify(s3UploadService, never()).deleteFileAfterCommit(
                "https://cdn.test/reviews/17-old.jpg",
                "reviews",
                17L
        );
    }

    @Test
    void successfulDatabaseSaveDeletesOnlyReplacedObject() {
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        S3UploadService s3UploadService = mock(S3UploadService.class);
        MultipartFile file = mock(MultipartFile.class);
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        Review review = Review.builder().id(17L).url("https://cdn.test/reviews/17-old.jpg").build();
        when(reviewRepository.findById(17L)).thenReturn(Optional.of(review));
        when(s3UploadService.uploadFile(file, "reviews", review.getUrl(), 17L))
                .thenReturn("https://cdn.test/reviews/17-new.jpg");
        when(reviewRepository.save(review)).thenReturn(review);

        ReviewFileController controller = new ReviewFileController(reviewRepository, s3UploadService);
        controller.uploadPhoto(17L, file, redirectAttributes);

        verify(s3UploadService).deleteFileAfterCommit(
                "https://cdn.test/reviews/17-old.jpg",
                "reviews",
                17L
        );
        verify(s3UploadService, never()).deleteFileAfterCommit(
                "https://cdn.test/reviews/17-new.jpg",
                "reviews",
                17L
        );
    }
}
