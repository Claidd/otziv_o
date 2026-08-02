package com.hunt.otziv.p_products.review.service;

import com.hunt.otziv.p_products.review.exception.PublicationApprovalException;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.services.ReviewService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPublicationApprovalServiceTest {

    @Mock
    private OrderDetailsService orderDetailsService;
    @Mock
    private ReviewService reviewService;
    @Mock
    private OrderStatusTransitionService orderStatusTransitionService;
    @Mock
    private BusinessAuditService businessAuditService;
    @Mock
    private OrderAggregateMutationLockService orderAggregateMutationLockService;

    @Test
    void approveAssignsDatesBeforeChangingStatusAndWritesAudit() throws Exception {
        OrderDetailsDTO details = details(review(7L, "Готовый текст", null));
        canonicalOrder(101L, details);
        when(reviewService.updateOrderDetailsAndReviewsAndPublishDates(101L, List.of(details))).thenReturn(true);
        when(orderStatusTransitionService.changeStatusForOrder(101L, "Публикация")).thenReturn(true);

        service().approvePreparedOrder(101L, List.of(details), "source=test");

        InOrder order = inOrder(
                orderAggregateMutationLockService,
                orderDetailsService,
                reviewService,
                orderStatusTransitionService,
                businessAuditService
        );
        order.verify(orderAggregateMutationLockService).lock(101L);
        order.verify(orderDetailsService).getOrderDetailDTOsByOrderIdForReviewCheck(101L);
        order.verify(reviewService).updateOrderDetailsAndReviewsAndPublishDates(101L, List.of(details));
        order.verify(orderStatusTransitionService).changeStatusForOrder(101L, "Публикация");
        order.verify(businessAuditService).recordSafely(
                "publication_allowed",
                "order_detail",
                details.getId(),
                101L,
                null,
                null,
                "Публикация",
                "reviews=1;source=test"
        );
    }

    @Test
    void invalidTextStopsBeforeDatesAndStatus() {
        OrderDetailsDTO details = details(review(7L, "Текст отзыва", null));
        canonicalOrder(101L, details);

        assertThatThrownBy(() -> service().approvePreparedOrder(101L, List.of(details), ""))
                .isInstanceOf(PublicationApprovalException.class)
                .hasMessageContaining("пустой или шаблонный текст")
                .hasMessageContaining("Решение:");

        verify(reviewService, never()).updateOrderDetailsAndReviewsAndPublishDates(any(), any());
        try {
            verify(orderStatusTransitionService, never()).changeStatusForOrder(any(), any());
        } catch (Exception ignored) {
            // Mockito verification keeps the checked signature of the production method.
        }
    }

    @Test
    void dateAssignmentFailureStopsBeforeStatus() {
        OrderDetailsDTO details = details(review(7L, "Готовый текст", null));
        canonicalOrder(101L, details);
        when(reviewService.updateOrderDetailsAndReviewsAndPublishDates(101L, List.of(details))).thenReturn(false);

        assertThatThrownBy(() -> service().approvePreparedOrder(101L, List.of(details), ""))
                .isInstanceOf(PublicationApprovalException.class)
                .hasMessageContaining("не удалось назначить даты публикации");

        try {
            verify(orderStatusTransitionService, never()).changeStatusForOrder(any(), any());
        } catch (Exception ignored) {
            // Mockito verification keeps the checked signature of the production method.
        }
        verify(businessAuditService, never()).recordSafely(
                eq("publication_allowed"),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void repairDoesNothingWhenEveryUnpublishedReviewAlreadyHasDate() {
        OrderDetailsDTO details = details(review(7L, "Готовый текст", LocalDate.of(2026, 7, 30)));
        when(orderDetailsService.getOrderDetailDTOsByOrderIdForReviewCheck(101L)).thenReturn(List.of(details));

        service().repairMissingDates(101L, "source=test");

        verify(reviewService, never()).updateOrderDetailsAndReviewsAndPublishDates(any(), any());
        verify(businessAuditService, never()).recordSafely(
                eq("publication_dates_repaired"),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void partialApprovalValidatesEveryDetailBeforeChangingWholeOrderStatus() {
        OrderDetailsDTO submitted = details(review(7L, "Готовый текст", null));
        OrderDetailsDTO other = details(review(8L, "Текст отзыва", null));
        when(orderDetailsService.getOrderDetailDTOsByOrderIdForReviewCheck(101L))
                .thenReturn(List.of(submitted, other));

        assertThatThrownBy(() -> service().approvePreparedOrder(101L, List.of(submitted), "source=public-link"))
                .isInstanceOf(PublicationApprovalException.class)
                .hasMessageContaining("пустой или шаблонный текст");

        verify(orderDetailsService).getOrderDetailDTOsByOrderIdForReviewCheck(101L);
        verify(reviewService, never()).updateOrderDetailsAndReviewsAndPublishDates(any(), any());
        try {
            verify(orderStatusTransitionService, never()).changeStatusForOrder(any(), any());
        } catch (Exception ignored) {
            // Mockito verification keeps the checked signature of the production method.
        }
    }

    @Test
    void emptySecondDetailProducesControlledApprovalFailureBeforeWrites() {
        OrderDetailsDTO submitted = details(review(7L, "Готовый текст", null));
        OrderDetailsDTO empty = OrderDetailsDTO.builder()
                .id(UUID.randomUUID())
                .reviews(List.of())
                .build();
        when(orderDetailsService.getOrderDetailDTOsByOrderIdForReviewCheck(101L))
                .thenReturn(List.of(submitted, empty));

        assertThatThrownBy(() -> service().approvePreparedOrder(101L, List.of(submitted), "source=public-link"))
                .isInstanceOf(PublicationApprovalException.class)
                .hasMessageContaining("отсутствуют отзывы");

        verify(reviewService, never()).updateOrderDetailsAndReviewsAndPublishDates(any(), any());
        try {
            verify(orderStatusTransitionService, never()).changeStatusForOrder(any(), any());
        } catch (Exception ignored) {
            // Mockito verification keeps the checked signature of the production method.
        }
    }

    private OrderPublicationApprovalService service() {
        return new OrderPublicationApprovalService(
                orderDetailsService,
                reviewService,
                orderStatusTransitionService,
                businessAuditService,
                orderAggregateMutationLockService
        );
    }

    private void canonicalOrder(Long orderId, OrderDetailsDTO details) {
        when(orderDetailsService.getOrderDetailDTOsByOrderIdForReviewCheck(orderId))
                .thenReturn(List.of(details));
    }

    private OrderDetailsDTO details(ReviewDTO review) {
        return OrderDetailsDTO.builder()
                .id(UUID.randomUUID())
                .reviews(List.of(review))
                .build();
    }

    private ReviewDTO review(Long id, String text, LocalDate publishedDate) {
        return ReviewDTO.builder()
                .id(id)
                .text(text)
                .publishedDate(publishedDate)
                .publish(false)
                .build();
    }
}
