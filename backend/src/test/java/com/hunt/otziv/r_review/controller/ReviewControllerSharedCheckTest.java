package com.hunt.otziv.r_review.controller;

import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.capability.ReviewCheckMutationLockService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.services.ReviewService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class ReviewControllerSharedCheckTest {

    private static final Long ORDER_ID = 101L;
    private static final Long REVIEW_ID = 501L;

    @Mock
    private ReviewService reviewService;

    @Mock
    private OrderDetailsService orderDetailsService;

    @Mock
    private OrderService orderService;

    @Mock
    private ProductService productService;

    @Mock
    private ReviewCheckMutationLockService mutationLockService;

    @Mock
    private RedirectAttributes redirectAttributes;

    @Mock
    private Model model;

    @Test
    void sharedSaveRejectsReviewIdOutsidePathCapabilityBeforeAnyWrite() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        OrderDetailsDTO submitted = submittedDetails(
                orderDetailId,
                ORDER_ID,
                ReviewDTO.builder().id(REVIEW_ID).text("valid before tampered").build(),
                ReviewDTO.builder().id(999L).text("tampered").build()
        );

        assertBadRequest(() -> controller().ReviewsEditPost(
                orderDetailId,
                submitted,
                redirectAttributes,
                model,
                null
        ));

        var ordered = inOrder(mutationLockService, orderDetailsService);
        ordered.verify(mutationLockService).lock(orderDetailId);
        ordered.verify(orderDetailsService).getOrderDetailDTOById(orderDetailId);
        verifyNoInteractions(reviewService, orderService);
    }

    @Test
    void sharedCorrectionRejectsOrderIdOutsidePathCapabilityBeforeAnyWrite() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        OrderDetailsDTO submitted = submittedDetails(
                orderDetailId,
                999L,
                ReviewDTO.builder().id(REVIEW_ID).text("valid review").build()
        );

        assertBadRequest(() -> controller().ReviewsEditPost2(
                orderDetailId,
                submitted,
                redirectAttributes,
                model,
                null
        ));

        verifyNoInteractions(reviewService, orderService);
    }

    @Test
    void sharedSaveUsesCanonicalPathOrderAndKeepsTextAndAnswerEditing() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        ReviewDTO submittedReview = ReviewDTO.builder()
                .id(REVIEW_ID)
                .orderDetailsId(orderDetailId)
                .text("Исправленный текст")
                .answer("Ответ клиента")
                .publishedDate(LocalDate.of(2099, 1, 1))
                .publish(true)
                .url("https://attacker.invalid")
                .build();

        controller().ReviewsEditPost(
                orderDetailId,
                submittedDetails(orderDetailId, null, submittedReview),
                redirectAttributes,
                model,
                null
        );

        ArgumentCaptor<OrderDetailsDTO> detailsCaptor = ArgumentCaptor.forClass(OrderDetailsDTO.class);
        ArgumentCaptor<ReviewDTO> reviewCaptor = ArgumentCaptor.forClass(ReviewDTO.class);
        verify(reviewService).updateOrderDetailAndReview(
                detailsCaptor.capture(),
                reviewCaptor.capture(),
                eq(REVIEW_ID)
        );
        assertThat(detailsCaptor.getValue().getId()).isEqualTo(orderDetailId);
        assertThat(detailsCaptor.getValue().getOrder().getId()).isEqualTo(ORDER_ID);
        assertThat(reviewCaptor.getValue().getText()).isEqualTo("Исправленный текст");
        assertThat(reviewCaptor.getValue().getAnswer()).isEqualTo("Ответ клиента");
        assertThat(reviewCaptor.getValue().getPublishedDate()).isNull();
        assertThat(reviewCaptor.getValue().isPublish()).isFalse();
        assertThat(reviewCaptor.getValue().getUrl()).isEqualTo("https://canonical.example/review");
    }

    @Test
    void sharedPayOkRejectsOrderIdOutsidePathCapabilityBeforeAnyWrite() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        OrderDetailsDTO submitted = submittedDetails(orderDetailId, 999L);

        assertBadRequest(() -> controller().OrderPayOkPost(
                submitted,
                redirectAttributes,
                model,
                null,
                orderDetailId
        ));

        verifyNoInteractions(reviewService, orderService);
    }

    @Test
    void sharedPayOkWithoutFormObjectUsesPathOrder() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        Order order = Order.builder()
                .id(ORDER_ID)
                .amount(1)
                .counter(1)
                .build();
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(OrderDetails.builder().id(orderDetailId).order(order).build());

        controller().OrderPayOkPost(
                OrderDetailsDTO.builder().build(),
                redirectAttributes,
                model,
                null,
                orderDetailId
        );

        verify(orderService).changeStatusForOrder(ORDER_ID, "Оплачено");
    }

    @Test
    void sharedPublicationUsesCanonicalOrderAndRelatedReviews() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        when(orderService.changeStatusForOrder(ORDER_ID, "Публикация")).thenReturn(true);
        when(reviewService.updateOrderDetailAndReviewAndPublishDate(any(OrderDetailsDTO.class)))
                .thenReturn(true);
        ReviewDTO submittedReview = ReviewDTO.builder()
                .id(REVIEW_ID)
                .orderDetailsId(orderDetailId)
                .text("Готовый текст отзыва")
                .answer("Ответ")
                .build();

        controller().ReviewsEditPostToPublish(
                orderDetailId,
                submittedDetails(orderDetailId, ORDER_ID, submittedReview),
                redirectAttributes,
                model,
                null
        );

        verify(reviewService).updateOrderDetailAndReview(any(OrderDetailsDTO.class), any(ReviewDTO.class), eq(REVIEW_ID));
        verify(orderService).changeStatusForOrder(ORDER_ID, "Публикация");
        verify(reviewService).updateOrderDetailAndReviewAndPublishDate(any(OrderDetailsDTO.class));
    }

    @Test
    void sharedCorrectionUsesCanonicalOrderAndRelatedReviews() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        ReviewDTO submittedReview = ReviewDTO.builder()
                .id(REVIEW_ID)
                .orderDetailsId(orderDetailId)
                .text("Нужна корректировка")
                .answer("Пожелание клиента")
                .build();

        controller().ReviewsEditPost2(
                orderDetailId,
                submittedDetails(orderDetailId, ORDER_ID, submittedReview),
                redirectAttributes,
                model,
                null
        );

        verify(reviewService).updateOrderDetailAndReview(any(OrderDetailsDTO.class), any(ReviewDTO.class), eq(REVIEW_ID));
        verify(orderService).changeStatusForOrder(ORDER_ID, "Коррекция");
    }

    private ReviewController controller() {
        return new ReviewController(
                reviewService,
                orderDetailsService,
                orderService,
                productService,
                mutationLockService
        );
    }

    private OrderDetailsDTO canonicalDetails(UUID orderDetailId) {
        return OrderDetailsDTO.builder()
                .id(orderDetailId)
                .order(OrderDTO.builder().id(ORDER_ID).build())
                .reviews(List.of(ReviewDTO.builder()
                        .id(REVIEW_ID)
                        .orderDetailsId(orderDetailId)
                        .text("Текущий текст")
                        .url("https://canonical.example/review")
                        .build()))
                .comment("Текущий комментарий")
                .build();
    }

    private OrderDetailsDTO submittedDetails(
            UUID orderDetailId,
            Long orderId,
            ReviewDTO... reviews
    ) {
        return OrderDetailsDTO.builder()
                .id(orderDetailId)
                .order(orderId == null ? null : OrderDTO.builder().id(orderId).build())
                .reviews(List.of(reviews))
                .comment("Комментарий клиента")
                .build();
    }

    private void assertBadRequest(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
