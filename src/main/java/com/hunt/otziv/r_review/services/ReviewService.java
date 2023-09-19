package com.hunt.otziv.r_review.services;

import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;

import java.util.List;

public interface ReviewService {

    Review save(Review review);

    List<Review> getReviewsAllByOrderId(Long id);

    void changeBot(Long id);

    void deActivateAndChangeBot(Long reviewId, Long botId);

    ReviewDTO getReviewDTOById(Long reviewId);
    Review getReviewById(Long reviewId);
    void updateReview(ReviewDTO reviewDTO, Long reviewId);

    void updateOrderDetailAndReview(OrderDetailsDTO orderDetailsDTO, ReviewDTO reviewDTO, Long reviewId);
}
