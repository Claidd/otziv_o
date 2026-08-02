package com.hunt.otziv.r_review.controller;

import com.hunt.otziv.manager.services.ManagerAccessService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.capability.service.ReviewCheckMutationLockService;
import com.hunt.otziv.r_review.capability.service.ReviewCheckPublicMutationPolicy;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;

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
    private OrderPublicationApprovalService publicationApprovalService;

    @Mock
    private ManagerAccessService managerAccessService;

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
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(liveDetails(orderDetailId, "На проверке", REVIEW_ID));
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
        verify(reviewService).updateOrderDetailAndReviews(detailsCaptor.capture());
        ReviewDTO savedReview = detailsCaptor.getValue().getReviews().getFirst();
        assertThat(detailsCaptor.getValue().getId()).isEqualTo(orderDetailId);
        assertThat(detailsCaptor.getValue().getOrder().getId()).isEqualTo(ORDER_ID);
        assertThat(savedReview.getText()).isEqualTo("Исправленный текст");
        assertThat(savedReview.getAnswer()).isEqualTo("Ответ клиента");
        assertThat(savedReview.getPublishedDate()).isNull();
        assertThat(savedReview.isPublish()).isFalse();
        assertThat(savedReview.getUrl()).isEqualTo("https://canonical.example/review");
    }

    @Test
    void sharedSaveAllowsCanonicalSubsetWithoutPublicationCompletenessCheck() {
        UUID orderDetailId = UUID.randomUUID();
        OrderDetailsDTO canonical = canonicalDetails(orderDetailId);
        canonical.setReviews(List.of(
                canonical.getReviews().getFirst(),
                ReviewDTO.builder().id(502L).text("Второй текущий текст").build()
        ));
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId)).thenReturn(canonical);
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(liveDetails(orderDetailId, "На проверке", REVIEW_ID, 502L));

        controller().ReviewsEditPost(
                orderDetailId,
                submittedDetails(
                        orderDetailId,
                        ORDER_ID,
                        ReviewDTO.builder().id(REVIEW_ID).text("Изменён только один отзыв").build()
                ),
                redirectAttributes,
                model,
                null
        );

        ArgumentCaptor<OrderDetailsDTO> detailsCaptor = ArgumentCaptor.forClass(OrderDetailsDTO.class);
        verify(reviewService).updateOrderDetailAndReviews(detailsCaptor.capture());
        assertThat(detailsCaptor.getValue().getReviews())
                .extracting(ReviewDTO::getId)
                .containsExactly(REVIEW_ID);
    }

    @Test
    void legacySharedFormRejectsTextAnswerAndCommentOverFiveThousandCodePoints() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        String oversized = "я".repeat(5_001);

        OrderDetailsDTO oversizedText = submittedDetails(
                orderDetailId,
                ORDER_ID,
                ReviewDTO.builder().id(REVIEW_ID).text(oversized).answer("ok").build()
        );
        OrderDetailsDTO oversizedAnswer = submittedDetails(
                orderDetailId,
                ORDER_ID,
                ReviewDTO.builder().id(REVIEW_ID).text("ok").answer(oversized).build()
        );
        OrderDetailsDTO oversizedComment = submittedDetails(
                orderDetailId,
                ORDER_ID,
                ReviewDTO.builder().id(REVIEW_ID).text("ok").answer("ok").build()
        );
        oversizedComment.setComment(oversized);

        for (OrderDetailsDTO submitted : List.of(oversizedText, oversizedAnswer, oversizedComment)) {
            assertBadRequest(() -> controller().ReviewsEditPost(
                    orderDetailId,
                    submitted,
                    redirectAttributes,
                    model,
                    null
            ));
        }

        verify(reviewService, never()).updateOrderDetailAndReviews(any());
        verify(orderDetailsService, never()).getOrderDetailById(orderDetailId);
    }

    @Test
    void legacySharedFormAcceptsFiveThousandCodePoints() {
        UUID orderDetailId = UUID.randomUUID();
        String boundary = "я".repeat(5_000);
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(liveDetails(orderDetailId, "На проверке", REVIEW_ID));
        OrderDetailsDTO submitted = submittedDetails(
                orderDetailId,
                ORDER_ID,
                ReviewDTO.builder().id(REVIEW_ID).text(boundary).answer(boundary).build()
        );
        submitted.setComment(boundary);

        controller().ReviewsEditPost(
                orderDetailId,
                submitted,
                redirectAttributes,
                model,
                null
        );

        verify(reviewService).updateOrderDetailAndReviews(any(OrderDetailsDTO.class));
    }

    @Test
    void assignedManagerKeepsSaveAccessAfterPublicMutationWindowCloses() {
        UUID orderDetailId = UUID.randomUUID();
        Authentication authentication = managerAuthentication();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(liveDetails(orderDetailId, "Оплачено", REVIEW_ID));
        when(managerAccessService.canAccessOrder(ORDER_ID, authentication)).thenReturn(true);

        controller().ReviewsEditPost(
                orderDetailId,
                submittedDetails(
                        orderDetailId,
                        ORDER_ID,
                        ReviewDTO.builder().id(REVIEW_ID).text("Правка менеджера").build()
                ),
                redirectAttributes,
                model,
                authentication
        );

        verify(reviewService).updateOrderDetailAndReviews(any(OrderDetailsDTO.class));
    }

    @Test
    void assignedWorkerKeepsSaveAccessAfterPublicMutationWindowCloses() {
        UUID orderDetailId = UUID.randomUUID();
        Authentication authentication = workerAuthentication();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(liveDetails(orderDetailId, "Оплачено", REVIEW_ID));
        when(managerAccessService.canAccessOrder(ORDER_ID, authentication)).thenReturn(true);

        controller().ReviewsEditPost(
                orderDetailId,
                submittedDetails(
                        orderDetailId,
                        ORDER_ID,
                        ReviewDTO.builder().id(REVIEW_ID).text("Правка исполнителя").build()
                ),
                redirectAttributes,
                model,
                authentication
        );

        verify(reviewService).updateOrderDetailAndReviews(any(OrderDetailsDTO.class));
    }

    @Test
    void assignedWorkerCannotApproveOrSendCorrectionThroughLegacyPublicRoutes() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        Authentication authentication = workerAuthentication();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(liveDetails(orderDetailId, "На проверке", REVIEW_ID));
        when(managerAccessService.canAccessOrder(ORDER_ID, authentication)).thenReturn(true);
        OrderDetailsDTO submitted = submittedDetails(
                orderDetailId,
                ORDER_ID,
                ReviewDTO.builder().id(REVIEW_ID).text("Готовый текст").build()
        );

        controller().ReviewsEditPostToPublish(
                orderDetailId,
                submitted,
                redirectAttributes,
                model,
                authentication
        );
        verify(redirectAttributes).addFlashAttribute(
                eq("errorMessage"),
                contains("Недостаточно прав")
        );

        assertThatThrownBy(() -> controller().ReviewsEditPost2(
                orderDetailId,
                submitted,
                redirectAttributes,
                model,
                authentication
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(reviewService, never()).updateOrderDetailAndReviews(any());
        verify(publicationApprovalService, never()).approvePreparedOrder(any(), any(), any());
        verify(orderService, never()).changeStatusForOrder(any(), eq("Коррекция"));
    }

    @Test
    void assignedManagerKeepsPublishAndCorrectionAccessAfterPublicMutationWindowCloses() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        Authentication authentication = managerAuthentication();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(liveDetails(orderDetailId, "Оплачено", REVIEW_ID));
        when(managerAccessService.canAccessOrder(ORDER_ID, authentication)).thenReturn(true);
        OrderDetailsDTO submitted = submittedDetails(
                orderDetailId,
                ORDER_ID,
                ReviewDTO.builder().id(REVIEW_ID).text("Решение менеджера").build()
        );

        controller().ReviewsEditPostToPublish(
                orderDetailId,
                submitted,
                redirectAttributes,
                model,
                authentication
        );
        controller().ReviewsEditPost2(
                orderDetailId,
                submitted,
                redirectAttributes,
                model,
                authentication
        );

        verify(reviewService, times(2)).updateOrderDetailAndReviews(any(OrderDetailsDTO.class));
        verify(publicationApprovalService).approvePreparedOrder(eq(ORDER_ID), any(), any());
        verify(orderService).changeStatusForOrder(ORDER_ID, "Коррекция");
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
                managerAuthentication(),
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
                managerAuthentication(),
                orderDetailId
        );

        var ordered = inOrder(mutationLockService, managerAccessService, orderService);
        ordered.verify(mutationLockService).lock(orderDetailId);
        ordered.verify(managerAccessService).requireOrderAccess(eq(ORDER_ID), any(Authentication.class));
        ordered.verify(orderService).changeStatusForOrder(ORDER_ID, "Оплачено");
    }

    @Test
    void sharedPublicationUsesCanonicalOrderAndRelatedReviews() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(liveDetails(orderDetailId, "На проверке", REVIEW_ID));
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

        verify(reviewService).updateOrderDetailAndReviews(any(OrderDetailsDTO.class));
        verify(publicationApprovalService).approvePreparedOrder(eq(ORDER_ID), any(), any());
        verify(orderService, never()).changeStatusForOrder(ORDER_ID, "Публикация");
    }

    @Test
    void sharedCorrectionUsesCanonicalOrderAndRelatedReviews() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(liveDetails(orderDetailId, "На проверке", REVIEW_ID));
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

        verify(reviewService).updateOrderDetailAndReviews(any(OrderDetailsDTO.class));
        verify(orderService).changeStatusForOrder(ORDER_ID, "Коррекция");
    }

    @Test
    void sharedCorrectionAllowsCanonicalSubsetWithoutPublicationCompletenessCheck() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        OrderDetailsDTO canonical = canonicalDetails(orderDetailId);
        canonical.setReviews(List.of(
                canonical.getReviews().getFirst(),
                ReviewDTO.builder().id(502L).text("Второй текущий текст").build()
        ));
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId)).thenReturn(canonical);
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(liveDetails(orderDetailId, "На проверке", REVIEW_ID, 502L));

        controller().ReviewsEditPost2(
                orderDetailId,
                submittedDetails(
                        orderDetailId,
                        ORDER_ID,
                        ReviewDTO.builder().id(REVIEW_ID).text("Исправлен один отзыв").build()
                ),
                redirectAttributes,
                model,
                null
        );

        verify(reviewService).updateOrderDetailAndReviews(any(OrderDetailsDTO.class));
        verify(orderService).changeStatusForOrder(ORDER_ID, "Коррекция");
    }

    @Test
    void sharedSaveRejectsTerminalOrderBeforeBatchWrite() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(liveDetails(orderDetailId, "Оплачено", REVIEW_ID));

        assertConflict(() -> controller().ReviewsEditPost(
                orderDetailId,
                submittedDetails(
                        orderDetailId,
                        ORDER_ID,
                        ReviewDTO.builder().id(REVIEW_ID).text("Новый текст").build()
                ),
                redirectAttributes,
                model,
                null
        ));

        verify(reviewService, never()).updateOrderDetailAndReviews(any());
        verifyNoInteractions(orderService, publicationApprovalService);
    }

    @Test
    void sharedCorrectionRejectsTerminalOrderBeforeBatchWrite() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(liveDetails(orderDetailId, "Оплачено", REVIEW_ID));

        assertConflict(() -> controller().ReviewsEditPost2(
                orderDetailId,
                submittedDetails(
                        orderDetailId,
                        ORDER_ID,
                        ReviewDTO.builder().id(REVIEW_ID).text("Новый текст").build()
                ),
                redirectAttributes,
                model,
                null
        ));

        verify(reviewService, never()).updateOrderDetailAndReviews(any());
        verify(orderService, never()).changeStatusForOrder(any(), eq("Коррекция"));
    }

    @Test
    void sharedPublicationRejectsIncompleteReviewSetBeforeWrites() {
        UUID orderDetailId = UUID.randomUUID();
        OrderDetailsDTO canonical = canonicalDetails(orderDetailId);
        canonical.setReviews(List.of(
                canonical.getReviews().getFirst(),
                ReviewDTO.builder().id(502L).text("Второй текст").build()
        ));
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId)).thenReturn(canonical);
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(liveDetails(orderDetailId, "На проверке", REVIEW_ID, 502L));

        String redirect = controller().ReviewsEditPostToPublish(
                orderDetailId,
                submittedDetails(
                        orderDetailId,
                        ORDER_ID,
                        ReviewDTO.builder().id(REVIEW_ID).text("Готовый текст").build()
                ),
                redirectAttributes,
                model,
                null
        );

        assertThat(redirect).isEqualTo("redirect:/review/editReviews/{orderDetailId}");
        verify(reviewService, never()).updateOrderDetailAndReviews(any());
        verifyNoInteractions(orderService, publicationApprovalService);
    }

    @Test
    void sharedPayOkRequiresObjectAccessBeforeStatusChange() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        Authentication authentication = managerAuthentication();
        when(orderDetailsService.getOrderDetailDTOById(orderDetailId))
                .thenReturn(canonicalDetails(orderDetailId));
        when(orderDetailsService.getOrderDetailById(orderDetailId))
                .thenReturn(liveDetails(orderDetailId, "Публикация", REVIEW_ID));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"))
                .when(managerAccessService).requireOrderAccess(ORDER_ID, authentication);

        assertThatThrownBy(() -> controller().OrderPayOkPost(
                OrderDetailsDTO.builder().build(),
                redirectAttributes,
                model,
                authentication,
                orderDetailId
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(orderService, never()).changeStatusForOrder(any(), eq("Оплачено"));
    }

    @Test
    void sharedPayOkRejectsAnonymousBeforeLoadingOrChangingOrder() {
        UUID orderDetailId = UUID.randomUUID();

        assertThatThrownBy(() -> controller().OrderPayOkPost(
                OrderDetailsDTO.builder().build(),
                redirectAttributes,
                model,
                null,
                orderDetailId
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verifyNoInteractions(mutationLockService, orderDetailsService, managerAccessService, orderService);
    }

    private ReviewController controller() {
        return new ReviewController(
                reviewService,
                orderDetailsService,
                orderService,
                productService,
                mutationLockService,
                publicationApprovalService,
                managerAccessService,
                new ReviewCheckPublicMutationPolicy()
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

    private OrderDetails liveDetails(UUID orderDetailId, String status, Long... reviewIds) {
        Order order = Order.builder()
                .id(ORDER_ID)
                .status(OrderStatus.builder().title(status).build())
                .amount(1)
                .counter(1)
                .build();
        List<Review> reviews = java.util.Arrays.stream(reviewIds)
                .map(id -> Review.builder().id(id).text("Текущий текст").build())
                .toList();
        return OrderDetails.builder()
                .id(orderDetailId)
                .order(order)
                .reviews(reviews)
                .build();
    }

    private Authentication managerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                "manager",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))
        );
    }

    private Authentication workerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                "worker",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_WORKER"))
        );
    }

    private void assertBadRequest(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private void assertConflict(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
