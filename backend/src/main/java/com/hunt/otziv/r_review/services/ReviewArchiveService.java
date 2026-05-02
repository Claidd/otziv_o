package com.hunt.otziv.r_review.services;

import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchive;

import java.util.List;

public interface ReviewArchiveService {
    void saveNewReviewArchive(Long review);

    Iterable<ReviewArchive> findAllReviews();
}
