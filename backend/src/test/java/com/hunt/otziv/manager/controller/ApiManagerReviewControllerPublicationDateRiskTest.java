package com.hunt.otziv.manager.controller;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.external_review_checks.service.ExternalReviewCheckService;
import com.hunt.otziv.manager.dto.api.ReviewEditorUpdateRequest;
import com.hunt.otziv.manager.services.ManagerAccessService;
import com.hunt.otziv.manager.services.ManagerBoardEditAssembler;
import com.hunt.otziv.manager.services.ManagerPermissionService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.reputationai.application.ReputationSingleReviewDraftService;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.s3.service.S3UploadService;
import com.hunt.otziv.text_generator.service.AutoTextService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import com.hunt.otziv.worker_activity.service.WorkerCredentialPreparationService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiManagerReviewControllerPublicationDateRiskTest {

    private static final long ORDER_ID = 100L;
    private static final long REVIEW_ID = 501L;
    private static final LocalDate PREVIOUS_DATE = LocalDate.of(2026, 7, 15);
    private static final LocalDate NEXT_DATE = LocalDate.of(2026, 7, 22);

    @Mock private CompanyService companyService;
    @Mock private OrderService orderService;
    @Mock private ProductService productService;
    @Mock private ReviewService reviewService;
    @Mock private AutoTextService autoTextService;
    @Mock private S3UploadService s3UploadService;
    @Mock private BadReviewTaskService badReviewTaskService;
    @Mock private ReviewRecoveryTaskService reviewRecoveryTaskService;
    @Mock private ReputationSingleReviewDraftService reputationSingleReviewDraftService;
    @Mock private UserService userService;
    @Mock private ManagerBoardEditAssembler managerBoardEditAssembler;
    @Mock private ManagerPermissionService managerPermissionService;
    @Mock private ManagerAccessService managerAccessService;
    @Mock private WorkerActivityService workerActivityService;
    @Mock private WorkerCredentialPreparationService credentialPreparationService;
    @Mock private ExternalReviewCheckService externalReviewCheckService;
    @Mock private Authentication authentication;

    @InjectMocks
    private ApiManagerReviewController controller;

    @Test
    void workerAllManualDateChangeRecordsRiskActivityWithSourceAndDates() {
        prepareReview(false);

        controller.updateOrderReview(ORDER_ID, REVIEW_ID, request(NEXT_DATE, "order-details", "worker-all", "all"), authentication);

        ArgumentCaptor<String> detailsCaptor = ArgumentCaptor.forClass(String.class);
        verify(workerActivityService).recordSafely(
                eq(authentication),
                eq(WorkerActivityAction.REVIEW_PUBLISH_DATE_UPDATE),
                eq("review"),
                eq(REVIEW_ID),
                eq(ORDER_ID),
                eq(REVIEW_ID),
                eq("all"),
                detailsCaptor.capture()
        );
        String details = detailsCaptor.getValue();
        assertTrue(details.contains("previousPublishedDate=2026-07-15;"));
        assertTrue(details.contains("newPublishedDate=2026-07-22;"));
        assertTrue(details.contains("sourcePage=order-details;"));
        assertTrue(details.contains("sourceEntry=worker-all;"));
        assertTrue(details.contains("sourceSection=all;"));
    }

    @Test
    void companyExceptionSuppressesManualDateRiskActivity() {
        prepareReview(true);

        controller.updateOrderReview(ORDER_ID, REVIEW_ID, request(NEXT_DATE, "order-details", "worker-all", "all"), authentication);

        verify(workerActivityService, never()).recordSafely(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void dateChangeWithoutWorkerAllSourceDoesNotRecordRiskActivity() {
        prepareReview(false);

        controller.updateOrderReview(ORDER_ID, REVIEW_ID, request(NEXT_DATE, "worker-board", null, "nagul"), authentication);

        verify(workerActivityService, never()).recordSafely(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unchangedDateDoesNotRecordRiskActivity() {
        prepareReview(false);

        controller.updateOrderReview(ORDER_ID, REVIEW_ID, request(PREVIOUS_DATE, "order-details", "worker-all", "all"), authentication);

        verify(workerActivityService, never()).recordSafely(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private void prepareReview(boolean ignoreRisk) {
        CompanyDTO company = CompanyDTO.builder()
                .id(12L)
                .ignoreWorkerPublicationDateRisk(ignoreRisk)
                .build();
        OrderDTO order = OrderDTO.builder()
                .id(ORDER_ID)
                .company(company)
                .build();
        OrderDetailsDTO details = OrderDetailsDTO.builder()
                .id(UUID.randomUUID())
                .order(order)
                .build();
        ReviewDTO review = ReviewDTO.builder()
                .id(REVIEW_ID)
                .text("Текст отзыва")
                .publishedDate(PREVIOUS_DATE)
                .orderDetails(details)
                .orderDetailsId(details.getId())
                .build();
        when(reviewService.getReviewDTOById(REVIEW_ID)).thenReturn(review);
        when(managerPermissionService.primaryReviewRole(authentication)).thenReturn("ROLE_WORKER");
    }

    private ReviewEditorUpdateRequest request(
            LocalDate publishedDate,
            String sourcePage,
            String sourceEntry,
            String sourceSection
    ) {
        return new ReviewEditorUpdateRequest(
                "Текст отзыва",
                "",
                "",
                null,
                null,
                publishedDate,
                false,
                false,
                "",
                "",
                null,
                null,
                "",
                sourcePage,
                sourceEntry,
                sourceSection
        );
    }
}
