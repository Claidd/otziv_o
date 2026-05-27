package com.hunt.otziv.r_review.services;

import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchive;

import java.util.List;

public interface ReviewArchiveService {
    void saveNewReviewArchive(Long review);

    void saveNewReviewArchive(Long review, String sourceReason);

    boolean existsByText(String text);

    boolean existsByTextExcludingOwnSource(String text, Long reviewId, Long orderId);

    Iterable<ReviewArchive> findAllReviews();
}
