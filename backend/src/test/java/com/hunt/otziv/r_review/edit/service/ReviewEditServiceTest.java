package com.hunt.otziv.r_review.edit.service;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.service.OrderDetailsService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewEditServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private OrderDetailsService orderDetailsService;

    @Mock
    private WorkerAssignmentMutationGuardService assignmentMutationGuardService;

    @Mock
    private OrderAggregateMutationLockService orderAggregateMutationLockService;

    @Test
    void updateReviewTextSavesReviewWhenReviewBelongsToOrder() {
        ReviewEditService service = service();
        Review review = reviewForOrder(10L);

        when(reviewRepository.findById(5L)).thenReturn(Optional.of(review));

        boolean updated = service.updateReviewText(10L, 5L, "Новый текст");

        assertTrue(updated);
        assertEquals("Новый текст", review.getText());
        verify(assignmentMutationGuardService).assertReview(5L);
        verify(orderAggregateMutationLockService).lockForReview(5L);
        verify(reviewRepository).save(review);
    }

    @Test
    void sharedReviewCheckAnswerDoesNotRequireAssignmentOwnership() {
        ReviewEditService service = service();
        Review review = reviewForOrder(10L);

        when(reviewRepository.findById(5L)).thenReturn(Optional.of(review));

        boolean updated = service.updateReviewAnswerFromSharedCheck(10L, 5L, "Один этаж");

        assertTrue(updated);
        assertEquals("Один этаж", review.getAnswer());
        verify(assignmentMutationGuardService, never()).assertReview(5L);
        verify(reviewRepository).save(review);
    }

    @Test
    void sharedReviewCheckTextDoesNotRequireAssignmentOwnership() {
        ReviewEditService service = service();
        Review review = reviewForOrder(10L);

        when(reviewRepository.findById(5L)).thenReturn(Optional.of(review));

        boolean updated = service.updateReviewTextFromSharedCheck(10L, 5L, "Исправленный текст");

        assertTrue(updated);
        assertEquals("Исправленный текст", review.getText());
        verify(assignmentMutationGuardService, never()).assertReview(5L);
        verify(reviewRepository).save(review);
    }

    @Test
    void updateReviewAnswerRejectsReviewFromAnotherOrder() {
        ReviewEditService service = service();
        Review review = reviewForOrder(11L);

        when(reviewRepository.findById(5L)).thenReturn(Optional.of(review));

        boolean updated = service.updateReviewAnswer(10L, 5L, "Ответ");

        assertFalse(updated);
        verify(orderAggregateMutationLockService).lockForReview(5L);
        verify(reviewRepository, never()).save(review);
    }

    @Test
    void updateReviewNoteSavesOrderDetailsComment() {
        ReviewEditService service = service();
        Review review = reviewForOrder(10L);
        OrderDetails orderDetails = review.getOrderDetails();

        when(reviewRepository.findById(5L)).thenReturn(Optional.of(review));

        boolean updated = service.updateReviewNote(10L, 5L, "Заметка");

        assertTrue(updated);
        assertEquals("Заметка", orderDetails.getComment());
        verify(assignmentMutationGuardService).assertReview(5L);
        verify(orderDetailsService).save(orderDetails);
    }

    @Test
    void updateReviewNoteRejectsMissingReview() {
        ReviewEditService service = service();

        when(reviewRepository.findById(404L)).thenReturn(Optional.empty());

        boolean updated = service.updateReviewNote(10L, 404L, "Заметка");

        assertFalse(updated);
        verify(orderDetailsService, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private ReviewEditService service() {
        return new ReviewEditService(
                reviewRepository,
                orderDetailsService,
                assignmentMutationGuardService,
                orderAggregateMutationLockService
        );
    }

    private Review reviewForOrder(Long orderId) {
        Order order = new Order();
        order.setId(orderId);

        OrderDetails orderDetails = new OrderDetails();
        orderDetails.setOrder(order);

        Review review = new Review();
        review.setOrderDetails(orderDetails);
        return review;
    }
}
