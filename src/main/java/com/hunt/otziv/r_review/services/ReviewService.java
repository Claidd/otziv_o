package com.hunt.otziv.r_review.services;

import com.hunt.otziv.r_review.model.Review;

import java.util.List;

public interface ReviewService {

    Review save(Review review);

    List<Review> getReviewsAllByOrderId(Long id);

    void changeBot(Long id);

    void deActivateAndChangeBot(Long reviewId, Long botId);
}
