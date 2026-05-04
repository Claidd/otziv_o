package com.hunt.otziv.r_review.edit;

import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ReviewEditService {

    private final ReviewRepository reviewRepository;
    private final OrderDetailsService orderDetailsService;

    @Transactional
    public boolean updateReviewText(Long orderId, Long reviewId, String text) {
        Review review = findReviewForOrder(orderId, reviewId);
        if (review == null) {
            return false;
        }

        review.setText(text);
        reviewRepository.save(review);
        return true;
    }

    @Transactional
    public boolean updateReviewAnswer(Long orderId, Long reviewId, String answer) {
        Review review = findReviewForOrder(orderId, reviewId);
        if (review == null) {
            return false;
        }

        review.setAnswer(answer);
        reviewRepository.save(review);
        return true;
    }

    @Transactional
    public boolean updateReviewNote(Long orderId, Long reviewId, String comment) {
        Review review = findReviewForOrder(orderId, reviewId);
        if (review == null || review.getOrderDetails() == null) {
            return false;
        }

        OrderDetails orderDetails = review.getOrderDetails();
        orderDetails.setComment(comment);
        orderDetailsService.save(orderDetails);
        return true;
    }

    private Review findReviewForOrder(Long orderId, Long reviewId) {
        Review review = reviewRepository.findById(reviewId).orElse(null);
        if (review == null || review.getOrderDetails() == null || review.getOrderDetails().getOrder() == null) {
            return null;
        }

        Long reviewOrderId = review.getOrderDetails().getOrder().getId();
        if (!Objects.equals(orderId, reviewOrderId)) {
            return null;
        }

        return review;
    }
}
