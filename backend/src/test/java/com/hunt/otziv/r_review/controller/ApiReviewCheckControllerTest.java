package com.hunt.otziv.r_review.controller;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import com.hunt.otziv.p_products.service.OrderDetailsService;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.capability.service.ReviewCheckMutationLockService;
import com.hunt.otziv.r_review.capability.service.ReviewCheckPublicMutationPolicy;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.archive.service.ReviewCheckArchiveService;
import com.hunt.otziv.archive.service.ReviewCheckArchiveService.ArchivedReviewCheck;
import com.hunt.otziv.archive.service.ReviewCheckArchiveService.ArchivedReviewCheckReview;
import com.hunt.otziv.archive.exception.ArchiveRestoreConflictException;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.webhook.security.WebhookClientIpResolver;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
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

    @Mock
    private OrderPublicationApprovalService publicationApprovalService;

    @Mock
    private ReviewCheckMutationLockService mutationLockService;

    @Mock
    private ManagerAccessService managerAccessService;

    @Mock
    private WebhookClientIpResolver clientIpResolver;

    @Test
    void anonymousResponseKeepsClientActionsButHidesInternalFields() {
        UUID orderDetailId = UUID.randomUUID();
        OrderDetails details = orderDetails(orderDetailId, "На проверке");
        details.getOrder().setFilial(Filial.builder().title("Филиал заказа").build());
        details.getReviews().getFirst().setFilial(Filial.builder().title("Филиал отзыва").build());
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId)).thenReturn(details);

        ApiReviewCheckController.ReviewCheckResponse response = controller()
                .getReviewCheck(orderDetailId, null);

        assertThat(response.archived()).isFalse();
        assertThat(response.orderId()).isNull();
        assertThat(response.companyId()).isNull();
        assertThat(response.workerFio()).isEmpty();
        assertThat(response.orderComments()).isEmpty();
        assertThat(response.companyComments()).isEmpty();
        assertThat(response.reviews()).hasSize(1);
        assertThat(response.reviews().getFirst().botName()).isEmpty();
        assertThat(response.reviews().getFirst().filialTitle()).isEqualTo("Филиал отзыва");
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
        when(managerAccessService.canAccessOrder(eq(101L), any(Authentication.class))).thenReturn(true);

        ApiReviewCheckController.ReviewCheckResponse response = controller()
                .getReviewCheck(orderDetailId, authentication("ROLE_MANAGER"));

        assertThat(response.orderId()).isEqualTo(101L);
        assertThat(response.companyId()).isEqualTo(202L);
        assertThat(response.workerFio()).isEqualTo("Специалист");
        assertThat(response.orderComments()).isEqualTo("internal order note");
        assertThat(response.companyComments()).isEqualTo("internal company note");
        assertThat(response.reviews().getFirst().botName()).isEqualTo("Bot Fio");
        assertThat(response.permissions().canOpenManagerLinks()).isTrue();
        assertThat(response.permissions().canApprovePublication()).isTrue();
        assertThat(response.permissions().canSendCorrection()).isTrue();
    }

    @Test
    void foreignManagerIsDowngradedToPublicLinkPermissions() {
        UUID orderDetailId = UUID.randomUUID();
        Authentication manager = authentication("ROLE_MANAGER");
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));
        when(managerAccessService.canAccessOrder(101L, manager)).thenReturn(false);

        ApiReviewCheckController.ReviewCheckResponse response = controller()
                .getReviewCheck(orderDetailId, manager);

        assertThat(response.permissions().authenticated()).isTrue();
        assertThat(response.permissions().canSeeInternalInfo()).isFalse();
        assertThat(response.permissions().canOpenManagerLinks()).isFalse();
        assertThat(response.permissions().canMarkPaid()).isFalse();
        assertThat(response.permissions().canEditNotes()).isFalse();
        assertThat(response.permissions().canSave()).isTrue();
        assertThat(response.permissions().canApprovePublication()).isTrue();
        assertThat(response.permissions().canSendCorrection()).isTrue();
        assertThat(response.orderId()).isNull();
        assertThat(response.workerFio()).isEmpty();
        assertThat(response.orderComments()).isEmpty();
        assertThat(response.companyComments()).isEmpty();
        assertThat(response.reviews().getFirst().botName()).isEmpty();
    }

    @Test
    void assignedWorkerCanSaveButCannotApproveOrSendCorrection() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        Authentication worker = authentication("ROLE_WORKER");
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));
        when(managerAccessService.canAccessOrder(101L, worker)).thenReturn(true);

        ApiReviewCheckController controller = controller();
        ApiReviewCheckController.ReviewCheckResponse response = controller.getReviewCheck(orderDetailId, worker);

        assertThat(response.permissions().canSeeInternalInfo()).isTrue();
        assertThat(response.permissions().canSave()).isTrue();
        assertThat(response.permissions().canApprovePublication()).isFalse();
        assertThat(response.permissions().canSendCorrection()).isFalse();

        ApiReviewCheckController.ReviewCheckUpdateRequest request = new ApiReviewCheckController.ReviewCheckUpdateRequest(
                "client comment",
                List.of(new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                        501L,
                        "worker text",
                        "worker answer",
                        null,
                        null,
                        null
                ))
        );

        assertThatThrownBy(() -> controller.approveReviews(
                orderDetailId,
                request,
                worker,
                new org.springframework.mock.web.MockHttpServletRequest()
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> controller.sendToCorrection(orderDetailId, request, worker))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(publicationApprovalService, never()).approvePreparedOrder(anyLong(), any(), any(), anyBoolean());
        verify(orderService, never()).changeStatusForOrder(101L, "Коррекция");
        verify(reviewService, never()).updateOrderDetailAndReviews(any());
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

        ArgumentCaptor<OrderDetailsDTO> detailsCaptor = ArgumentCaptor.forClass(OrderDetailsDTO.class);
        verify(reviewService).updateOrderDetailAndReviews(detailsCaptor.capture());
        ReviewDTO savedReview = detailsCaptor.getValue().getReviews().getFirst();
        assertThat(savedReview.getText()).isEqualTo("client text");
        assertThat(savedReview.getAnswer()).isEqualTo("client answer");
        assertThat(savedReview.isPublish()).isFalse();
        assertThat(savedReview.getPublishedDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(savedReview.getUrl()).isEqualTo("https://real.example/review");
    }

    @Test
    void publicMutationLocksResourceBeforeReadingAndRejectsDuplicateReviewIds() {
        UUID orderDetailId = UUID.randomUUID();
        OrderDetails details = orderDetails(orderDetailId, "На проверке");
        Review first = details.getReviews().getFirst();
        Review second = Review.builder()
                .id(502L)
                .text("second text")
                .answer("")
                .orderDetails(details)
                .build();
        details.setReviews(List.of(first, second));
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId)).thenReturn(details);

        ApiReviewCheckController.ReviewCheckReviewUpdateRequest duplicate =
                new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                        501L,
                        "text",
                        "answer",
                        null,
                        null,
                        null
                );

        assertThatThrownBy(() -> controller().saveReviews(
                orderDetailId,
                new ApiReviewCheckController.ReviewCheckUpdateRequest(
                        "comment",
                        List.of(duplicate, duplicate)
                ),
                null
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        var ordered = inOrder(mutationLockService, orderDetailsService);
        ordered.verify(mutationLockService).lock(orderDetailId);
        ordered.verify(orderDetailsService).getOrderDetailForReviewCheckById(orderDetailId);
        verify(reviewService, never()).updateOrderDetailAndReviews(any());
    }

    @Test
    void anonymousApproveAllowsPublicationWithClientTextChanges() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        String bearerReferer = "https://o-ogo.ru/review/editReviews/" + orderDetailId;
        var servletRequest = new org.springframework.mock.web.MockHttpServletRequest();
        servletRequest.addHeader("Referer", bearerReferer);
        servletRequest.addHeader("X-Forwarded-For", "198.51.100.99, 203.0.113.10");
        when(clientIpResolver.resolve(servletRequest)).thenReturn("203.0.113.10");
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));
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
                servletRequest
        );

        ArgumentCaptor<OrderDetailsDTO> detailsCaptor = ArgumentCaptor.forClass(OrderDetailsDTO.class);
        verify(reviewService).updateOrderDetailAndReviews(detailsCaptor.capture());
        assertThat(detailsCaptor.getValue().getReviews().getFirst().getText()).isEqualTo("client changed text");
        assertThat(detailsCaptor.getValue().getReviews().getFirst().getAnswer()).isEqualTo("client changed answer");
        ArgumentCaptor<String> auditDetailsCaptor = ArgumentCaptor.forClass(String.class);
        verify(publicationApprovalService).approvePreparedOrder(
                eq(101L),
                any(),
                auditDetailsCaptor.capture(),
                eq(false)
        );
        assertThat(auditDetailsCaptor.getValue())
                .contains("ip=203.0.113.10")
                .doesNotContain(orderDetailId.toString(), bearerReferer, "referer=", "198.51.100.99");
        verify(orderService, never()).changeStatusForOrder(101L, "Коррекция");
    }

    @Test
    void approvalAuditBoundsAndSanitizesUntrustedHeaderValues() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        var servletRequest = new org.springframework.mock.web.MockHttpServletRequest();
        servletRequest.addHeader("User-Agent", "agent=spoof;\u0001\u0085\u2028\u2029" + "x".repeat(400));
        servletRequest.addHeader("Origin", "https://origin.example/a=b;c");
        servletRequest.addHeader("Referer", "https://o-ogo.ru/review/editReviews/" + orderDetailId);
        when(clientIpResolver.resolve(servletRequest)).thenReturn("203.0.113.11");
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));

        controller().approveReviews(
                orderDetailId,
                new ApiReviewCheckController.ReviewCheckUpdateRequest(
                        "client comment",
                        List.of(new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                                501L,
                                "client text",
                                "",
                                null,
                                null,
                                null
                        ))
                ),
                null,
                servletRequest
        );

        ArgumentCaptor<String> auditCaptor = ArgumentCaptor.forClass(String.class);
        verify(publicationApprovalService).approvePreparedOrder(eq(101L), any(), auditCaptor.capture(), eq(false));
        String audit = auditCaptor.getValue();
        String userAgent = auditValue(audit, "userAgent");
        String origin = auditValue(audit, "origin");

        assertThat(userAgent.codePointCount(0, userAgent.length())).isLessThanOrEqualTo(256);
        assertThat(userAgent)
                .doesNotContain("=", ";", "\u0001", "\u0085", "\u2028", "\u2029");
        assertThat(origin).isEqualTo("https://origin.example/a,b,c");
        assertThat(audit)
                .contains("ip=203.0.113.11;")
                .doesNotContain("referer=", orderDetailId.toString());
    }

    @Test
    void approveRejectsSubsetThatCouldHideAnotherBlankReview() {
        UUID orderDetailId = UUID.randomUUID();
        OrderDetails details = orderDetails(orderDetailId, "На проверке");
        Review omitted = Review.builder()
                .id(502L)
                .text("   ")
                .orderDetails(details)
                .build();
        details.setReviews(List.of(details.getReviews().getFirst(), omitted));
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId)).thenReturn(details);

        assertThatThrownBy(() -> controller().approveReviews(
                orderDetailId,
                new ApiReviewCheckController.ReviewCheckUpdateRequest(
                        "client comment",
                        List.of(new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                                501L,
                                "only submitted review",
                                "",
                                false,
                                null,
                                ""
                        ))
                ),
                null,
                new org.springframework.mock.web.MockHttpServletRequest()
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(publicationApprovalService, never()).approvePreparedOrder(anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void foreignWorkerCannotUseInternalSendToCheckAction() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        Authentication worker = authentication("ROLE_WORKER");
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));
        when(managerAccessService.canAccessOrder(101L, worker)).thenReturn(false);

        assertThatThrownBy(() -> controller().sendToCheck(orderDetailId, null, worker))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(orderService, never()).changeStatusForOrder(101L, "В проверку");
    }

    @Test
    void anonymousLinkHolderCanEditReviewTextAndAnswer() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));
        when(reviewService.updateReviewTextFromSharedCheck(101L, 501L, "Исправленный текст"))
                .thenReturn(true);
        when(reviewService.updateReviewAnswerFromSharedCheck(101L, 501L, "Уточнение клиента"))
                .thenReturn(true);

        ApiReviewCheckController controller = controller();
        ApiReviewCheckController.ReviewCheckReviewResponse textResponse = controller.updateReviewText(
                orderDetailId,
                501L,
                new ApiReviewCheckController.ReviewCheckReviewTextUpdateRequest("Исправленный текст"),
                null
        );
        ApiReviewCheckController.ReviewCheckReviewResponse answerResponse = controller.updateReviewAnswer(
                orderDetailId,
                501L,
                new ApiReviewCheckController.ReviewCheckReviewAnswerUpdateRequest("Уточнение клиента"),
                null
        );

        assertThat(textResponse.id()).isEqualTo(501L);
        assertThat(answerResponse.id()).isEqualTo(501L);
        verify(reviewService).updateReviewTextFromSharedCheck(101L, 501L, "Исправленный текст");
        verify(reviewService).updateReviewAnswerFromSharedCheck(101L, 501L, "Уточнение клиента");
    }

    @Test
    void publicTextAndAnswerAcceptDatabaseBoundaryButRejectOneCodePointMore() {
        UUID orderDetailId = UUID.randomUUID();
        String maxText = "т".repeat(5_000);
        String maxAnswer = "а".repeat(5_000);
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));
        when(reviewService.updateReviewTextFromSharedCheck(101L, 501L, maxText)).thenReturn(true);
        when(reviewService.updateReviewAnswerFromSharedCheck(101L, 501L, maxAnswer)).thenReturn(true);

        controller().updateReviewText(
                orderDetailId,
                501L,
                new ApiReviewCheckController.ReviewCheckReviewTextUpdateRequest(maxText),
                null
        );
        controller().updateReviewAnswer(
                orderDetailId,
                501L,
                new ApiReviewCheckController.ReviewCheckReviewAnswerUpdateRequest(maxAnswer),
                null
        );

        assertThatThrownBy(() -> controller().updateReviewText(
                orderDetailId,
                501L,
                new ApiReviewCheckController.ReviewCheckReviewTextUpdateRequest(maxText + "т"),
                null
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> controller().updateReviewAnswer(
                orderDetailId,
                501L,
                new ApiReviewCheckController.ReviewCheckReviewAnswerUpdateRequest(maxAnswer + "а"),
                null
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sharedFormCommentAndChangedHttpUrlHonorDatabaseBoundaries() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));
        when(managerAccessService.canAccessOrder(eq(101L), any(Authentication.class))).thenReturn(true);
        String prefix = "https://example.com/";
        String maxUrl = prefix + "a".repeat(2_048 - prefix.length());
        String maxComment = "к".repeat(5_000);
        ApiReviewCheckController.ReviewCheckReviewUpdateRequest review =
                new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                        501L,
                        "Текст",
                        "Ответ",
                        false,
                        null,
                        maxUrl
                );

        controller().saveReviews(
                orderDetailId,
                new ApiReviewCheckController.ReviewCheckUpdateRequest(maxComment, List.of(review)),
                authentication("ROLE_MANAGER")
        );

        ArgumentCaptor<OrderDetailsDTO> captured = ArgumentCaptor.forClass(OrderDetailsDTO.class);
        verify(reviewService).updateOrderDetailAndReviews(captured.capture());
        assertThat(captured.getValue().getReviews().getFirst().getUrl()).isEqualTo(maxUrl);

        assertThatThrownBy(() -> controller().saveReviews(
                orderDetailId,
                new ApiReviewCheckController.ReviewCheckUpdateRequest(maxComment + "к", List.of(review)),
                authentication("ROLE_MANAGER")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ApiReviewCheckController.ReviewCheckReviewUpdateRequest oversizedUrl =
                new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                        501L,
                        "Текст",
                        "Ответ",
                        false,
                        null,
                        maxUrl + "a"
                );
        assertThatThrownBy(() -> controller().saveReviews(
                orderDetailId,
                new ApiReviewCheckController.ReviewCheckUpdateRequest(maxComment, List.of(oversizedUrl)),
                authentication("ROLE_MANAGER")
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void changedReviewUrlRejectsNonWebUserinfoAndControlButUnchangedLegacyValueSurvives() {
        UUID orderDetailId = UUID.randomUUID();
        OrderDetails details = orderDetails(orderDetailId, "На проверке");
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId)).thenReturn(details);
        when(managerAccessService.canAccessOrder(eq(101L), any(Authentication.class))).thenReturn(true);

        for (String invalidUrl : List.of(
                "javascript:alert(1)",
                "https://user:password@example.com/review",
                "https://example.com/review\nInjected"
        )) {
            ApiReviewCheckController.ReviewCheckReviewUpdateRequest changed =
                    new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                            501L,
                            "Текст",
                            "Ответ",
                            false,
                            null,
                            invalidUrl
                    );
            assertThatThrownBy(() -> controller().saveReviews(
                    orderDetailId,
                    new ApiReviewCheckController.ReviewCheckUpdateRequest("", List.of(changed)),
                    authentication("ROLE_MANAGER")
            ))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        details.getReviews().getFirst().setUrl("javascript:legacy-value");
        ApiReviewCheckController.ReviewCheckReviewUpdateRequest unchanged =
                new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                        501L,
                        "Текст",
                        "Ответ",
                        false,
                        null,
                        "javascript:legacy-value"
                );
        controller().saveReviews(
                orderDetailId,
                new ApiReviewCheckController.ReviewCheckUpdateRequest("", List.of(unchanged)),
                authentication("ROLE_MANAGER")
        );

        verify(reviewService).updateOrderDetailAndReviews(any(OrderDetailsDTO.class));
    }

    @Test
    void anonymousLinkHolderCanSendReviewsToCorrection() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));
        when(orderService.changeStatusForOrder(101L, "Коррекция")).thenReturn(true);

        controller().sendToCorrection(
                orderDetailId,
                new ApiReviewCheckController.ReviewCheckUpdateRequest(
                        "Нужна корректировка",
                        List.of(new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                                501L,
                                "Исправьте текст",
                                "Комментарий клиента",
                                false,
                                null,
                                ""
                        ))
                ),
                null
        );

        verify(reviewService).updateOrderDetailAndReviews(any(OrderDetailsDTO.class));
        verify(orderService).changeStatusForOrder(101L, "Коррекция");
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

        verify(reviewService, never()).updateReviewTextFromSharedCheck(anyLong(), anyLong(), any());
    }

    @Test
    void workerReviewerCanSaveAnswerThroughSharedReviewCheck() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));
        when(reviewService.updateReviewAnswerFromSharedCheck(101L, 501L, "Один этаж"))
                .thenReturn(true);

        ApiReviewCheckController.ReviewCheckReviewResponse response = controller()
                .updateReviewAnswer(
                        orderDetailId,
                        501L,
                        new ApiReviewCheckController.ReviewCheckReviewAnswerUpdateRequest("Один этаж"),
                        authentication("ROLE_WORKER")
                );

        assertThat(response.id()).isEqualTo(501L);
        verify(reviewService).updateReviewAnswerFromSharedCheck(101L, 501L, "Один этаж");
        verify(reviewService, never()).updateReviewAnswer(anyLong(), anyLong(), any());
    }

    @Test
    void authenticatedClientCanSaveTextThroughSharedReviewCheck() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));
        when(reviewService.updateReviewTextFromSharedCheck(101L, 501L, "Исправленный текст"))
                .thenReturn(true);

        ApiReviewCheckController.ReviewCheckReviewResponse response = controller()
                .updateReviewText(
                        orderDetailId,
                        501L,
                        new ApiReviewCheckController.ReviewCheckReviewTextUpdateRequest("Исправленный текст"),
                        authentication("ROLE_CLIENT")
                );

        assertThat(response.id()).isEqualTo(501L);
        verify(reviewService).updateReviewTextFromSharedCheck(101L, 501L, "Исправленный текст");
        verify(reviewService, never()).updateReviewText(anyLong(), anyLong(), any());
    }

    @Test
    void managerCanSaveAnswerThroughSharedReviewCheck() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "На проверке"));
        when(reviewService.updateReviewAnswerFromSharedCheck(101L, 501L, "Уточните этаж"))
                .thenReturn(true);

        ApiReviewCheckController.ReviewCheckReviewResponse response = controller()
                .updateReviewAnswer(
                        orderDetailId,
                        501L,
                        new ApiReviewCheckController.ReviewCheckReviewAnswerUpdateRequest("Уточните этаж"),
                        authentication("ROLE_MANAGER")
                );

        assertThat(response.id()).isEqualTo(501L);
        verify(reviewService).updateReviewAnswerFromSharedCheck(101L, 501L, "Уточните этаж");
        verify(reviewService, never()).updateReviewAnswer(anyLong(), anyLong(), any());
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
    void anonymousLiveArchiveOrderKeepsClientReviewActions() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(orderDetails(orderDetailId, "Архив"));

        ApiReviewCheckController.ReviewCheckResponse response = controller()
                .getReviewCheck(orderDetailId, null);

        assertThat(response.archived()).isFalse();
        assertThat(response.status()).isEqualTo("Архив");
        assertThat(response.permissions().canSave()).isTrue();
        assertThat(response.permissions().canApprovePublication()).isTrue();
        assertThat(response.permissions().canSendCorrection()).isTrue();
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

        assertThat(response.archived()).isTrue();
        assertThat(response.status()).isEqualTo("Архив");
        assertThat(response.permissions().canSave()).isTrue();
        assertThat(response.permissions().canSendCorrection()).isTrue();
    }

    @Test
    void archivedInternalFieldsRequireTheArchivedAssignmentScope() {
        UUID orderDetailId = UUID.randomUUID();
        Authentication relatedManager = authentication("ROLE_MANAGER");
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenThrow(new UsernameNotFoundException("not live"));
        when(reviewCheckArchiveService.findByOrderDetailId(orderDetailId))
                .thenReturn(Optional.of(archivedReviewCheck(orderDetailId)));
        when(managerAccessService.canAccessArchivedOrder(707L, 303L, relatedManager)).thenReturn(true);

        ApiReviewCheckController.ReviewCheckResponse related = controller()
                .getReviewCheck(orderDetailId, relatedManager);

        assertThat(related.archived()).isTrue();
        assertThat(related.orderId()).isEqualTo(101L);
        assertThat(related.companyId()).isEqualTo(202L);
        assertThat(related.workerFio()).isEqualTo("Специалист");
        assertThat(related.orderComments()).isEqualTo("internal order note");
        assertThat(related.reviews().getFirst().botName()).isEqualTo("Bot Fio");
        assertThat(related.permissions().canOpenManagerLinks()).isTrue();
        assertThat(related.permissions().canMarkPaid()).isTrue();

        Authentication relatedWorker = authentication("ROLE_WORKER");
        when(managerAccessService.canAccessArchivedOrder(707L, 303L, relatedWorker)).thenReturn(true);
        ApiReviewCheckController.ReviewCheckResponse worker = controller()
                .getReviewCheck(orderDetailId, relatedWorker);
        assertThat(worker.permissions().canSave()).isTrue();
        assertThat(worker.permissions().canApprovePublication()).isFalse();
        assertThat(worker.permissions().canSendCorrection()).isFalse();
        assertThat(worker.permissions().canOpenManagerLinks()).isFalse();
        assertThat(worker.permissions().canMarkPaid()).isFalse();

        Authentication foreignManager = authentication("ROLE_MANAGER");
        when(managerAccessService.canAccessArchivedOrder(707L, 303L, foreignManager)).thenReturn(false);
        ApiReviewCheckController.ReviewCheckResponse foreign = controller()
                .getReviewCheck(orderDetailId, foreignManager);

        assertThat(foreign.workerFio()).isEmpty();
        assertThat(foreign.orderComments()).isEmpty();
        assertThat(foreign.companyComments()).isEmpty();
        assertThat(foreign.reviews().getFirst().botName()).isEmpty();
        assertThat(foreign.permissions().canSave()).isTrue();
        assertThat(foreign.permissions().canApprovePublication()).isTrue();
        assertThat(foreign.permissions().canSendCorrection()).isTrue();
        assertThat(foreign.permissions().canOpenManagerLinks()).isFalse();
        assertThat(foreign.permissions().canMarkPaid()).isFalse();
    }

    @Test
    void archivedCurrentCompanyCommentsRequireCurrentCompanyScope() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenThrow(new UsernameNotFoundException("not live"));
        when(reviewCheckArchiveService.findByOrderDetailId(orderDetailId))
                .thenReturn(Optional.of(archivedReviewCheck(orderDetailId)));

        Authentication oldManager = authentication("ROLE_MANAGER");
        when(managerAccessService.canAccessArchivedOrder(707L, 303L, oldManager)).thenReturn(true);
        when(managerAccessService.canAccessCompany(202L, oldManager)).thenReturn(false);

        ApiReviewCheckController.ReviewCheckResponse oldManagerResponse = controller()
                .getReviewCheck(orderDetailId, oldManager);

        assertThat(oldManagerResponse.orderComments()).isEqualTo("internal order note");
        assertThat(oldManagerResponse.companyComments()).isEmpty();
        assertThat(oldManagerResponse.reviews().getFirst().commentCompany()).isEmpty();

        Authentication currentAdmin = authentication("ROLE_ADMIN");
        when(managerAccessService.canAccessArchivedOrder(707L, 303L, currentAdmin)).thenReturn(true);
        when(managerAccessService.canAccessCompany(202L, currentAdmin)).thenReturn(true);

        ApiReviewCheckController.ReviewCheckResponse currentAdminResponse = controller()
                .getReviewCheck(orderDetailId, currentAdmin);

        assertThat(currentAdminResponse.companyComments()).isEqualTo("internal company note");
        assertThat(currentAdminResponse.reviews().getFirst().commentCompany())
                .isEqualTo("internal company note");
    }

    @Test
    void terminalPaidArchivedResponseIsReadOnly() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenThrow(new UsernameNotFoundException("not live"));
        when(reviewCheckArchiveService.findByOrderDetailId(orderDetailId))
                .thenReturn(Optional.of(archivedReviewCheck(orderDetailId, true)));

        ApiReviewCheckController.ReviewCheckResponse response = controller()
                .getReviewCheck(orderDetailId, null);

        assertThat(response.status()).isEqualTo("Архив");
        assertThat(response.reviews()).hasSize(1);
        assertThat(response.permissions().canSave()).isFalse();
        assertThat(response.permissions().canApprovePublication()).isFalse();
        assertThat(response.permissions().canSendCorrection()).isFalse();
    }

    @Test
    void terminalPaidArchivedReviewCannotBeRestoredByAnonymousMutation() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenThrow(new UsernameNotFoundException("not live"));
        when(reviewCheckArchiveService.restoreByOrderDetailId(
                orderDetailId,
                "Коррекция",
                "anonymous-review-check"
        )).thenThrow(new ArchiveRestoreConflictException("read only"));

        assertThatThrownBy(() -> controller().updateReviewText(
                orderDetailId,
                501L,
                new ApiReviewCheckController.ReviewCheckReviewTextUpdateRequest("client text"),
                null
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException responseException = (ResponseStatusException) exception;
                    assertThat(responseException.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(responseException.getReason()).isEqualTo("read only");
                });

        verify(reviewService, never()).updateReviewTextFromSharedCheck(anyLong(), anyLong(), any());
    }

    @Test
    void saveArchivedReviewCheckRestoresOrderToCorrectionAndPersistsChanges() {
        UUID orderDetailId = UUID.randomUUID();
        OrderDetails restoredDetails = orderDetails(orderDetailId, "Коррекция");
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenThrow(new UsernameNotFoundException("not live"))
                .thenReturn(restoredDetails)
                .thenReturn(restoredDetails);

        ApiReviewCheckController.ReviewCheckResponse response = controller()
                .saveReviews(
                        orderDetailId,
                        new ApiReviewCheckController.ReviewCheckUpdateRequest(
                                "client comment",
                                List.of(new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                                        501L,
                                        "client text",
                                        "client answer",
                                        false,
                                        null,
                                        ""
                                ))
                        ),
                        null
                );

        verify(reviewCheckArchiveService).restoreByOrderDetailId(
                orderDetailId,
                "Коррекция",
                "anonymous-review-check"
        );
        ArgumentCaptor<OrderDetailsDTO> detailsCaptor = ArgumentCaptor.forClass(OrderDetailsDTO.class);
        verify(reviewService).updateOrderDetailAndReviews(detailsCaptor.capture());
        assertThat(detailsCaptor.getValue().getReviews().getFirst().getText()).isEqualTo("client text");
        assertThat(detailsCaptor.getValue().getReviews().getFirst().getAnswer()).isEqualTo("client answer");
        assertThat(response.status()).isEqualTo("Коррекция");
    }

    @Test
    void anonymousApproveRestoresArchiveToReviewBeforePublishing() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        OrderDetails restoredDetails = orderDetails(orderDetailId, "На проверке");
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenThrow(new UsernameNotFoundException("not live"))
                .thenReturn(restoredDetails)
                .thenReturn(restoredDetails);

        ApiReviewCheckController.ReviewCheckResponse response = controller().approveReviews(
                orderDetailId,
                new ApiReviewCheckController.ReviewCheckUpdateRequest(
                        "client comment",
                        List.of(new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                                501L,
                                "approved client text",
                                "",
                                false,
                                null,
                                ""
                        ))
                ),
                null,
                new org.springframework.mock.web.MockHttpServletRequest()
        );

        verify(reviewCheckArchiveService).restoreByOrderDetailId(
                orderDetailId,
                "На проверке",
                "anonymous-review-check"
        );
        verify(publicationApprovalService).approvePreparedOrder(eq(101L), any(), any(), eq(true));
        assertThat(response.status()).isEqualTo("На проверке");
    }

    @Test
    void updateArchivedReviewTextRestoresOrderToCorrection() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        OrderDetails restoredDetails = orderDetails(orderDetailId, "Коррекция");
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenThrow(new UsernameNotFoundException("not live"))
                .thenReturn(restoredDetails)
                .thenReturn(restoredDetails);
        when(reviewService.updateReviewTextFromSharedCheck(101L, 501L, "client text")).thenReturn(true);

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
        verify(reviewService).updateReviewTextFromSharedCheck(101L, 501L, "client text");
        assertThat(response.id()).isEqualTo(501L);
    }

    @Test
    void managerCanMarkArchivedPublishedOrderPaidThroughNormalStatusTransition() throws Exception {
        UUID orderDetailId = UUID.randomUUID();
        Authentication manager = authentication("ROLE_MANAGER");
        OrderDetails restoredDetails = orderDetails(orderDetailId, "Опубликовано");
        restoredDetails.getReviews().getFirst().setPublish(true);
        restoredDetails.getOrder().setCounter(1);
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenThrow(new UsernameNotFoundException("not live"))
                .thenReturn(restoredDetails)
                .thenReturn(restoredDetails);
        when(managerAccessService.canAccessOrder(101L, manager)).thenReturn(true);
        when(orderService.changeStatusForOrder(101L, "Оплачено")).thenReturn(true);

        ApiReviewCheckController.ReviewCheckResponse response = controller()
                .markPaid(orderDetailId, manager);

        verify(reviewCheckArchiveService).restoreByOrderDetailId(
                orderDetailId,
                "Опубликовано",
                "manager"
        );
        verify(orderService).changeStatusForOrder(101L, "Оплачено");
        assertThat(response.archived()).isFalse();
    }

    private ApiReviewCheckController controller() {
        return new ApiReviewCheckController(
                orderDetailsService,
                orderService,
                reviewService,
                companyService,
                reviewCheckArchiveService,
                businessAuditService,
                publicationApprovalService,
                mutationLockService,
                managerAccessService,
                clientIpResolver,
                new ReviewCheckPublicMutationPolicy()
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
        return archivedReviewCheck(orderDetailId, false);
    }

    private ArchivedReviewCheck archivedReviewCheck(UUID orderDetailId, boolean terminalPaidOrder) {
        return new ArchivedReviewCheck(
                orderDetailId,
                101L,
                202L,
                707L,
                303L,
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
                terminalPaidOrder,
                List.of(new ArchivedReviewCheckReview(
                        501L,
                        "current text",
                        "current answer",
                        "Bot Fio",
                        "Филиал архива",
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

    private String auditValue(String audit, String key) {
        String prefix = key + "=";
        int start = audit.indexOf(prefix);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int valueStart = start + prefix.length();
        int end = audit.indexOf(';', valueStart);
        assertThat(end).isGreaterThanOrEqualTo(valueStart);
        return audit.substring(valueStart, end);
    }
}
