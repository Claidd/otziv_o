package com.hunt.otziv.r_review.edit;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
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

    @Test
    void updateReviewTextSavesReviewWhenReviewBelongsToOrder() {
        ReviewEditService service = service();
        Review review = reviewForOrder(10L);

        when(reviewRepository.findById(5L)).thenReturn(Optional.of(review));

        boolean updated = service.updateReviewText(10L, 5L, "Новый текст");

        assertTrue(updated);
        assertEquals("Новый текст", review.getText());
        verify(reviewRepository).save(review);
    }

    @Test
    void updateReviewAnswerRejectsReviewFromAnotherOrder() {
        ReviewEditService service = service();
        Review review = reviewForOrder(11L);

        when(reviewRepository.findById(5L)).thenReturn(Optional.of(review));

        boolean updated = service.updateReviewAnswer(10L, 5L, "Ответ");

        assertFalse(updated);
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
        return new ReviewEditService(reviewRepository, orderDetailsService);
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
