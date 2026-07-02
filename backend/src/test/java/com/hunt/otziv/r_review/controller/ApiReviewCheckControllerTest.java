package com.hunt.otziv.r_review.controller;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.archive.service.ReviewCheckArchiveService;
import com.hunt.otziv.archive.service.ReviewCheckArchiveService.ArchivedReviewCheck;
import com.hunt.otziv.archive.service.ReviewCheckArchiveService.ArchivedReviewCheckReview;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiReviewCheckControllerTest {

    @Mock
    private OrderDetailsService orderDetailsService;

    @Mock
    private OrderService orderService;

    @Mock
    private ReviewService reviewService;

    @Mock
    private CompanyService companyService;

    @Mock
    private ReviewCheckArchiveService reviewCheckArchiveService;

    @Mock
    private BusinessAuditService businessAuditService;

    @Test
    void anonymousResponseKeepsClientActionsButHidesInternalFields() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));

        ApiReviewCheckController.ReviewCheckResponse response = controller()
                .getReviewCheck(orderDetailId, null);

        assertThat(response.orderId()).isNull();
        assertThat(response.companyId()).isNull();
        assertThat(response.workerFio()).isEmpty();
        assertThat(response.orderComments()).isEmpty();
        assertThat(response.companyComments()).isEmpty();
        assertThat(response.reviews()).hasSize(1);
        assertThat(response.reviews().getFirst().botName()).isEmpty();
        assertThat(response.permissions().authenticated()).isFalse();
        assertThat(response.permissions().canSeeInternalInfo()).isFalse();
        assertThat(response.permissions().canSave()).isTrue();
        assertThat(response.permissions().canApprovePublication()).isTrue();
        assertThat(response.permissions().canSendCorrection()).isTrue();
    }

    @Test
    void managerResponseKeepsInternalNavigationAndBotName() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));

        ApiReviewCheckController.ReviewCheckResponse response = controller()
                .getReviewCheck(orderDetailId, authentication("ROLE_MANAGER"));

        assertThat(response.orderId()).isEqualTo(101L);
        assertThat(response.companyId()).isEqualTo(202L);
        assertThat(response.workerFio()).isEqualTo("Специалист");
        assertThat(response.orderComments()).isEqualTo("internal order note");
        assertThat(response.companyComments()).isEqualTo("internal company note");
        assertThat(response.reviews().getFirst().botName()).isEqualTo("Bot Fio");
        assertThat(response.permissions().canOpenManagerLinks()).isTrue();
    }

    @Test
    void anonymousSaveIgnoresPublicationAndUrlFieldsFromRequest() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));

        controller().saveReviews(
                orderDetailId,
                new ApiReviewCheckController.ReviewCheckUpdateRequest(
                        "client comment",
                        List.of(new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                                501L,
                                "client text",
                                "client answer",
                                true,
                                "2026-06-10",
                                "https://evil.example/review"
                        ))
                ),
                null
        );

        ArgumentCaptor<ReviewDTO> reviewCaptor = ArgumentCaptor.forClass(ReviewDTO.class);
        verify(reviewService).updateOrderDetailAndReview(any(OrderDetailsDTO.class), reviewCaptor.capture(), eq(501L));
        ReviewDTO savedReview = reviewCaptor.getValue();
        assertThat(savedReview.getText()).isEqualTo("client text");
        assertThat(savedReview.getAnswer()).isEqualTo("client answer");
        assertThat(savedReview.isPublish()).isFalse();
        assertThat(savedReview.getPublishedDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(savedReview.getUrl()).isEqualTo("https://real.example/review");
    }

    @Test
    void anonymousApproveAllowsPublicationWithClientTextChanges() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));
        when(orderService.changeStatusForOrder(101L, "Публикация")).thenReturn(true);
        when(reviewService.updateOrderDetailAndReviewAndPublishDate(any())).thenReturn(true);

        controller().approveReviews(
                orderDetailId,
                new ApiReviewCheckController.ReviewCheckUpdateRequest(
                        "client comment",
                        List.of(new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                                501L,
                                "client changed text",
                                "client changed answer",
                                false,
                                "2026-06-01",
                                "https://real.example/review"
                        ))
                ),
                null,
                null
        );

        ArgumentCaptor<ReviewDTO> reviewCaptor = ArgumentCaptor.forClass(ReviewDTO.class);
        verify(reviewService).updateOrderDetailAndReview(any(OrderDetailsDTO.class), reviewCaptor.capture(), eq(501L));
        assertThat(reviewCaptor.getValue().getText()).isEqualTo("client changed text");
        assertThat(reviewCaptor.getValue().getAnswer()).isEqualTo("client changed answer");
        verify(orderService).changeStatusForOrder(101L, "Публикация");
        verify(orderService, never()).changeStatusForOrder(101L, "Коррекция");
        verify(reviewService).updateOrderDetailAndReviewAndPublishDate(any());
    }

    @Test
    void anonymousCannotUpdateReviewOutsideCurrentUuid() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));

        assertThatThrownBy(() -> controller().updateReviewText(
                orderDetailId,
                999L,
                new ApiReviewCheckController.ReviewCheckReviewTextUpdateRequest("new text"),
                null
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(reviewService, never()).updateReviewText(anyLong(), anyLong(), any());
    }

    @Test
    void anonymousLiveClosedOrderCannotBeMutated() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "Оплачено"));

        ApiReviewCheckController.ReviewCheckResponse response = controller()
                .getReviewCheck(orderDetailId, null);

        assertThat(response.permissions().canSave()).isFalse();
        assertThat(response.permissions().canApprovePublication()).isFalse();
        assertThat(response.permissions().canSendCorrection()).isFalse();

        assertThatThrownBy(() -> controller().saveReviews(
                orderDetailId,
                new ApiReviewCheckController.ReviewCheckUpdateRequest(
                        "",
                        List.of(new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                                501L,
                                "client text",
                                "",
                                false,
                                null,
                                ""
                        ))
                ),
                null
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anonymousArchivedResponseAllowsClientTextEditing() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenThrow(new UsernameNotFoundException("not live"));
        when(reviewCheckArchiveService.findByOrderDetailId(orderDetailId))
                .thenReturn(Optional.of(archivedReviewCheck(orderDetailId)));

        ApiReviewCheckController.ReviewCheckResponse response = controller()
                .getReviewCheck(orderDetailId, null);

        assertThat(response.status()).isEqualTo("Архив");
        assertThat(response.permissions().canSave()).isTrue();
        assertThat(response.permissions().canSendCorrection()).isTrue();
    }

    @Test
    void updateArchivedReviewTextRestoresOrderToCorrection() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        OrderDetails restoredDetails = orderDetails(orderDetailId, "Коррекция");
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenThrow(new UsernameNotFoundException("not live"))
                .thenReturn(restoredDetails)
                .thenReturn(restoredDetails);
        when(reviewService.updateReviewText(101L, 501L, "client text")).thenReturn(true);

        ApiReviewCheckController.ReviewCheckReviewResponse response = controller()
                .updateReviewText(
                        orderDetailId,
                        501L,
                        new ApiReviewCheckController.ReviewCheckReviewTextUpdateRequest("client text"),
                        null
                );

        verify(reviewCheckArchiveService).restoreByOrderDetailId(
                orderDetailId,
                "Коррекция",
                "anonymous-review-check"
        );
        verify(reviewService).updateReviewText(101L, 501L, "client text");
        assertThat(response.id()).isEqualTo(501L);
    }

    private ApiReviewCheckController controller() {
        return new ApiReviewCheckController(
                orderDetailsService,
                orderService,
                reviewService,
                companyService,
                reviewCheckArchiveService,
                businessAuditService
        );
    }

    private OrderDetails orderDetails(UUID orderDetailId, String status) {
        Company company = Company.builder()
                .id(202L)
                .title("Company")
                .commentsCompany("internal company note")
                .build();
        Worker worker = Worker.builder()
                .id(303L)
                .user(User.builder().fio("Специалист").build())
                .build();
        Order order = Order.builder()
                .id(101L)
                .company(company)
                .worker(worker)
                .status(OrderStatus.builder().title(status).build())
                .zametka("internal order note")
                .amount(1)
                .counter(0)
                .build();
        OrderDetails orderDetails = OrderDetails.builder()
                .id(orderDetailId)
                .order(order)
                .amount(1)
                .comment("client comment")
                .build();
        Review review = Review.builder()
                .id(501L)
                .text("current text")
                .answer("current answer")
                .publish(false)
                .publishedDate(LocalDate.of(2026, 6, 1))
                .url("https://real.example/review")
                .bot(Bot.builder()
                        .fio("Bot Fio")
                        .login("bot-login")
                        .password("bot-password")
                        .build())
                .orderDetails(orderDetails)
                .worker(worker)
                .build();
        orderDetails.setReviews(List.of(review));
        return orderDetails;
    }

    private ArchivedReviewCheck archivedReviewCheck(UUID orderDetailId) {
        return new ArchivedReviewCheck(
                orderDetailId,
                101L,
                202L,
                "Company",
                "Filial",
                "Архив",
                "Специалист",
                "internal order note",
                "internal company note",
                "client comment",
                1,
                0,
                BigDecimal.valueOf(1000),
                List.of(new ArchivedReviewCheckReview(
                        501L,
                        "current text",
                        "current answer",
                        "Bot Fio",
                        "Отзыв 2ГИС",
                        false,
                        "https://real.example/review",
                        LocalDate.of(2026, 6, 1),
                        false
                ))
        );
    }

    private Authentication authentication(String... authorities) {
        return new UsernamePasswordAuthenticationToken(
                "manager",
                "password",
                java.util.Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList()
        );
    }
}
