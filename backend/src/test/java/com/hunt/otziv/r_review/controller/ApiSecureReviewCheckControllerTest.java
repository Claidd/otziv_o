package com.hunt.otziv.r_review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.r_review.capability.model.ReviewCheckCapabilityScope;
import com.hunt.otziv.r_review.capability.service.ReviewCheckCapabilityService;
import com.hunt.otziv.r_review.capability.service.ReviewCheckCapabilityService.ResolvedCapability;
import com.hunt.otziv.r_review.controller.ApiReviewCheckController.ReviewCheckPermissions;
import com.hunt.otziv.r_review.controller.ApiReviewCheckController.ReviewCheckResponse;
import com.hunt.otziv.r_review.controller.ApiReviewCheckController.ReviewCheckReviewResponse;
import com.hunt.otziv.r_review.controller.ApiReviewCheckController.ReviewCheckUpdateRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

class ApiSecureReviewCheckControllerTest {

    @Test
    void scopedResponseDoesNotDiscloseLegacyUuidOrInternalPermissions() {
        ReviewCheckCapabilityService capabilityService = mock(ReviewCheckCapabilityService.class);
        ApiReviewCheckController delegate = mock(ApiReviewCheckController.class);
        UUID orderDetailId = UUID.randomUUID();
        long mask = ReviewCheckCapabilityScope.VIEW.bit() | ReviewCheckCapabilityScope.EDIT.bit();
        when(capabilityService.resolveAndTouch("opaque", ReviewCheckCapabilityScope.VIEW, "view"))
                .thenReturn(new ResolvedCapability(5L, orderDetailId, mask, LocalDateTime.now().plusDays(1)));
        when(delegate.getReviewCheck(orderDetailId, null)).thenReturn(response(orderDetailId));

        ResponseEntity<ReviewCheckResponse> result = new ApiSecureReviewCheckController(capabilityService, delegate)
                .get("opaque");

        assertThat(result.getHeaders().getCacheControl()).isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().orderDetailId()).isEqualTo(new UUID(0L, 0L));
        assertThat(result.getBody().orderId()).isNull();
        assertThat(result.getBody().companyId()).isNull();
        assertThat(result.getBody().permissions().canSave()).isTrue();
        assertThat(result.getBody().permissions().canApprovePublication()).isFalse();
        assertThat(result.getBody().permissions().canSendCorrection()).isFalse();
        assertThat(result.getBody().permissions().canOpenManagerLinks()).isFalse();
        assertThat(result.getBody().reviews()).singleElement().satisfies(review -> {
            assertThat(review.botName()).isEmpty();
            assertThat(review.filialTitle()).isEqualTo("Филиал отзыва");
            assertThat(review.comment()).isEmpty();
            assertThat(review.orderComments()).isEmpty();
            assertThat(review.commentCompany()).isEmpty();
        });
        verify(delegate).getReviewCheck(orderDetailId, null);
    }

    @Test
    void textMutationResolvesEditScopeBeforeCallingLegacyCompatibleLogic() {
        ReviewCheckCapabilityService capabilityService = mock(ReviewCheckCapabilityService.class);
        ApiReviewCheckController delegate = mock(ApiReviewCheckController.class);
        UUID orderDetailId = UUID.randomUUID();
        when(capabilityService.resolveAndTouch("opaque", ReviewCheckCapabilityScope.EDIT, "edit"))
                .thenReturn(new ResolvedCapability(
                        5L,
                        orderDetailId,
                        ReviewCheckCapabilityScope.EDIT.bit(),
                        LocalDateTime.now().plusDays(1)
                ));
        ApiReviewCheckController.ReviewCheckReviewTextUpdateRequest request =
                new ApiReviewCheckController.ReviewCheckReviewTextUpdateRequest("Новый текст");
        when(delegate.updateReviewText(orderDetailId, 17L, request, null))
                .thenReturn(response(orderDetailId).reviews().get(0));

        ResponseEntity<ReviewCheckReviewResponse> result = new ApiSecureReviewCheckController(capabilityService, delegate)
                .updateText("opaque", 17L, request);

        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().botName()).isEmpty();
        assertThat(result.getBody().comment()).isEmpty();
        assertThat(result.getBody().orderComments()).isEmpty();
        assertThat(result.getBody().commentCompany()).isEmpty();
        verify(delegate).updateReviewText(orderDetailId, 17L, request, null);
    }

    @Test
    void approveAndCorrectionUseIndependentScopes() throws Exception {
        ReviewCheckCapabilityService capabilityService = mock(ReviewCheckCapabilityService.class);
        ApiReviewCheckController delegate = mock(ApiReviewCheckController.class);
        UUID orderDetailId = UUID.randomUUID();
        ReviewCheckResponse response = response(orderDetailId);
        ReviewCheckUpdateRequest request = new ReviewCheckUpdateRequest(
                "Попытка изменить комментарий",
                List.of(new ApiReviewCheckController.ReviewCheckReviewUpdateRequest(
                        17L,
                        "Попытка изменить текст",
                        "Попытка изменить ответ",
                        true,
                        "2026-08-01",
                        "https://example.test/review"
                ))
        );
        when(capabilityService.resolveAndTouch("approve-token", ReviewCheckCapabilityScope.APPROVE, "approve"))
                .thenReturn(new ResolvedCapability(
                        6L,
                        orderDetailId,
                        ReviewCheckCapabilityScope.APPROVE.bit(),
                        LocalDateTime.now().plusDays(1)
                ));
        when(capabilityService.resolveAndTouch(
                "correction-token",
                ReviewCheckCapabilityScope.SEND_CORRECTION,
                "correction"
        )).thenReturn(new ResolvedCapability(
                7L,
                orderDetailId,
                ReviewCheckCapabilityScope.SEND_CORRECTION.bit(),
                LocalDateTime.now().plusDays(1)
        ));
        when(delegate.approveReviews(eq(orderDetailId), any(ReviewCheckUpdateRequest.class), isNull(), isNull()))
                .thenReturn(response);
        when(delegate.sendToCorrection(eq(orderDetailId), any(ReviewCheckUpdateRequest.class), isNull()))
                .thenReturn(response);
        ApiSecureReviewCheckController controller = new ApiSecureReviewCheckController(capabilityService, delegate);

        controller.approve("approve-token", request, null);
        controller.correction("correction-token", request);

        verify(capabilityService).resolveAndTouch(
                "approve-token",
                ReviewCheckCapabilityScope.APPROVE,
                "approve"
        );
        verify(capabilityService).resolveAndTouch(
                "correction-token",
                ReviewCheckCapabilityScope.SEND_CORRECTION,
                "correction"
        );
        ArgumentCaptor<ReviewCheckUpdateRequest> approveRequest = ArgumentCaptor.forClass(ReviewCheckUpdateRequest.class);
        ArgumentCaptor<ReviewCheckUpdateRequest> correctionRequest = ArgumentCaptor.forClass(ReviewCheckUpdateRequest.class);
        verify(delegate).approveReviews(eq(orderDetailId), approveRequest.capture(), isNull(), isNull());
        verify(delegate).sendToCorrection(eq(orderDetailId), correctionRequest.capture(), isNull());
        assertActionOnlyRequest(approveRequest.getValue());
        assertActionOnlyRequest(correctionRequest.getValue());
    }

    private void assertActionOnlyRequest(ReviewCheckUpdateRequest request) {
        assertThat(request.comment()).isNull();
        assertThat(request.reviews()).singleElement().satisfies(review -> {
            assertThat(review.id()).isEqualTo(17L);
            assertThat(review.text()).isNull();
            assertThat(review.answer()).isNull();
            assertThat(review.publish()).isNull();
            assertThat(review.publishedDate()).isNull();
            assertThat(review.url()).isNull();
        });
    }

    private ReviewCheckResponse response(UUID orderDetailId) {
        return new ReviewCheckResponse(
                orderDetailId,
                false,
                11L,
                22L,
                "Компания",
                "Филиал",
                "На проверке",
                "Внутренний исполнитель",
                "Внутренняя заметка",
                "Внутренняя заметка компании",
                "Комментарий клиента",
                1,
                0,
                BigDecimal.TEN,
                false,
                List.of(new ReviewCheckReviewResponse(
                        17L,
                        "Текст",
                        "Ответ",
                        "Секретный бот",
                        "Филиал отзыва",
                        "Внутренний комментарий",
                        "Внутренняя заметка заказа",
                        "Внутренняя заметка компании",
                        "Продукт",
                        false,
                        "",
                        "",
                        false
                )),
                new ReviewCheckPermissions(false, false, false, true, true, true, false, false, false, false)
        );
    }
}
