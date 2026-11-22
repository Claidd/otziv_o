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
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

        when(orderAggregateMutationLockService.lockIfLiveForReview(5L)).thenReturn(Optional.of(orderOf(review)));
        when(reviewRepository.findById(5L)).thenReturn(Optional.of(review));

        boolean updated = service.updateReviewText(10L, 5L, "Новый текст");

        assertTrue(updated);
        assertEquals("Новый текст", review.getText());
        verify(assignmentMutationGuardService).assertReview(5L);
        verify(orderAggregateMutationLockService).lockIfLiveForReview(5L);
        verify(reviewRepository).save(review);
    }

    @Test
    void sharedReviewCheckAnswerDoesNotRequireAssignmentOwnership() {
        ReviewEditService service = service();
        Review review = reviewForOrder(10L);

        when(orderAggregateMutationLockService.lockIfLiveForReview(5L)).thenReturn(Optional.of(orderOf(review)));
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

        when(orderAggregateMutationLockService.lockIfLiveForReview(5L)).thenReturn(Optional.of(orderOf(review)));
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

        when(orderAggregateMutationLockService.lockIfLiveForReview(5L)).thenReturn(Optional.of(orderOf(review)));
        when(reviewRepository.findById(5L)).thenReturn(Optional.of(review));

        boolean updated = service.updateReviewAnswer(10L, 5L, "Ответ");

        assertFalse(updated);
        verify(orderAggregateMutationLockService).lockIfLiveForReview(5L);
        verify(reviewRepository, never()).save(review);
    }

    @Test
    void updateReviewNoteSavesOrderDetailsComment() {
        ReviewEditService service = service();
        Review review = reviewForOrder(10L);
        OrderDetails orderDetails = review.getOrderDetails();

        when(orderAggregateMutationLockService.lockIfLiveForReview(5L)).thenReturn(Optional.of(orderOf(review)));
        when(reviewRepository.findById(5L)).thenReturn(Optional.of(review));

        boolean updated = service.updateReviewNote(10L, 5L, "Заметка");

        assertTrue(updated);
        assertEquals("Заметка", orderDetails.getComment());
        verify(assignmentMutationGuardService).assertReview(5L);
        verify(orderDetailsService).save(orderDetails);
    }

    @Test
    void updateReviewNoteRejectsReviewDeletedAfterParentLock() {
        ReviewEditService service = service();

        when(orderAggregateMutationLockService.lockIfLiveForReview(404L))
                .thenReturn(Optional.of(Order.builder().id(10L).build()));
        when(reviewRepository.findById(404L)).thenReturn(Optional.empty());

        boolean updated = service.updateReviewNote(10L, 404L, "Заметка");

        assertFalse(updated);
        verify(orderDetailsService, never()).save(any());
    }

    @Test
    void deletedReviewTextSaveReportsNotFoundWithoutThrowingThroughLock() {
        ReviewEditService service = service();

        when(orderAggregateMutationLockService.lockIfLiveForReview(404L)).thenReturn(Optional.empty());

        boolean updated = service.updateReviewText(10L, 404L, "Новый текст");

        // A false result is what the controllers turn into a clean 404; the
        // throwing lock must not be touched, or the shared transaction would be
        // marked rollback-only and the commit would answer 500.
        assertFalse(updated);
        verify(orderAggregateMutationLockService, never()).lockForReview(anyLong());
        verify(reviewRepository, never()).findById(anyLong());
        verify(reviewRepository, never()).save(any());
        verify(assignmentMutationGuardService, never()).assertReview(anyLong());
    }

    @Test
    void deletedReviewAnswerSaveReportsNotFound() {
        ReviewEditService service = service();

        when(orderAggregateMutationLockService.lockIfLiveForReview(404L)).thenReturn(Optional.empty());

        boolean updated = service.updateReviewAnswer(10L, 404L, "Ответ");

        assertFalse(updated);
        verify(orderAggregateMutationLockService, never()).lockForReview(anyLong());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void deletedReviewNoteSaveReportsNotFound() {
        ReviewEditService service = service();

        when(orderAggregateMutationLockService.lockIfLiveForReview(404L)).thenReturn(Optional.empty());

        boolean updated = service.updateReviewNote(10L, 404L, "Заметка");

        assertFalse(updated);
        verify(orderAggregateMutationLockService, never()).lockForReview(anyLong());
        verify(orderDetailsService, never()).save(any());
    }

    @Test
    void deletedReviewSharedCheckSavesReportNotFound() {
        ReviewEditService service = service();

        when(orderAggregateMutationLockService.lockIfLiveForReview(404L)).thenReturn(Optional.empty());

        assertFalse(service.updateReviewTextFromSharedCheck(10L, 404L, "Текст"));
        assertFalse(service.updateReviewAnswerFromSharedCheck(10L, 404L, "Ответ"));
        verify(orderAggregateMutationLockService, never()).lockForReview(anyLong());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void deletedReviewWorkerTextSaveReportsNotFound() {
        ReviewEditService service = service();
        Authentication authentication = new UsernamePasswordAuthenticationToken("worker", "n/a");

        when(orderAggregateMutationLockService.lockIfLiveForReview(404L)).thenReturn(Optional.empty());

        boolean updated = service.updateReviewText(10L, 404L, "Новый текст", authentication);

        assertFalse(updated);
        verify(assignmentMutationGuardService).assertReview(404L, authentication);
        verify(orderAggregateMutationLockService, never()).lockForReview(anyLong());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void movedReviewStillAnswersConflict() {
        ReviewEditService service = service();

        when(orderAggregateMutationLockService.lockIfLiveForReview(5L)).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Состав заказа изменился. Обновите страницу и повторите действие."
        ));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.updateReviewText(10L, 5L, "Новый текст")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void missingIdsSkipTheLockEntirely() {
        ReviewEditService service = service();

        assertFalse(service.updateReviewText(null, 5L, "Текст"));
        assertFalse(service.updateReviewText(10L, null, "Текст"));
        verify(orderAggregateMutationLockService, never()).lockIfLiveForReview(any());
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

    private Order orderOf(Review review) {
        return review.getOrderDetails().getOrder();
    }
}
