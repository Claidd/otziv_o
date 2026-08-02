package com.hunt.otziv.r_review.edit.service;

import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ReviewEditService {

    private final ReviewRepository reviewRepository;
    private final OrderDetailsService orderDetailsService;
    private final WorkerAssignmentMutationGuardService assignmentMutationGuardService;
    private final OrderAggregateMutationLockService orderAggregateMutationLockService;

    @Transactional
    public boolean updateReviewText(Long orderId, Long reviewId, String text) {
        return updateReviewTextInternal(orderId, reviewId, text, true);
    }

    @Transactional
    public boolean updateReviewTextFromSharedCheck(Long orderId, Long reviewId, String text) {
        return updateReviewTextInternal(orderId, reviewId, text, false);
    }

    private boolean updateReviewTextInternal(Long orderId, Long reviewId, String text, boolean requireAssignment) {
        Review review = findReviewForOrder(orderId, reviewId);
        if (review == null) {
            return false;
        }
        if (requireAssignment) {
            assignmentMutationGuardService.assertReview(reviewId);
        }

        review.setText(text);
        reviewRepository.save(review);
        return true;
    }

    @Transactional
    public boolean updateReviewAnswer(Long orderId, Long reviewId, String answer) {
        return updateReviewAnswerInternal(orderId, reviewId, answer, true);
    }

    @Transactional
    public boolean updateReviewAnswerFromSharedCheck(Long orderId, Long reviewId, String answer) {
        return updateReviewAnswerInternal(orderId, reviewId, answer, false);
    }

    private boolean updateReviewAnswerInternal(Long orderId, Long reviewId, String answer, boolean requireAssignment) {
        Review review = findReviewForOrder(orderId, reviewId);
        if (review == null) {
            return false;
        }
        if (requireAssignment) {
            assignmentMutationGuardService.assertReview(reviewId);
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
        assignmentMutationGuardService.assertReview(reviewId);

        OrderDetails orderDetails = review.getOrderDetails();
        orderDetails.setComment(comment);
        orderDetailsService.save(orderDetails);
        return true;
    }

    private Review findReviewForOrder(Long orderId, Long reviewId) {
        if (orderId == null || reviewId == null) {
            return null;
        }
        try {
            // The review id determines the only parent row we ever lock. A
            // mismatched caller-supplied order id is rejected afterwards, so
            // crossed (order A, review B) requests cannot create A<->B cycles.
            orderAggregateMutationLockService.lockForReview(reviewId);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                return null;
            }
            throw exception;
        }
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
