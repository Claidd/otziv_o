package com.hunt.otziv.manager.controller;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.external_review_checks.service.ExternalReviewCheckService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.manager.service.ManagerBoardEditAssembler;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.p_products.service.ProductService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.reputationai.application.service.ReputationSingleReviewDraftService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.s3.service.S3UploadService;
import com.hunt.otziv.security.credentials.CredentialRevealRequest;
import com.hunt.otziv.security.credentials.CredentialRevealResponse;
import com.hunt.otziv.security.credentials.service.CredentialRevealService;
import com.hunt.otziv.text_generator.service.AutoTextService;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import com.hunt.otziv.worker_activity.service.WorkerCredentialPreparationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiManagerReviewControllerCredentialRevealTest {

    private static final Long ORDER_ID = 10L;
    private static final Long REVIEW_ID = 20L;
    private static final Long BAD_TASK_ID = 30L;
    private static final Long RECOVERY_TASK_ID = 40L;

    @Mock
    private CompanyService companyService;
    @Mock
    private OrderService orderService;
    @Mock
    private ProductService productService;
    @Mock
    private ReviewService reviewService;
    @Mock
    private AutoTextService autoTextService;
    @Mock
    private S3UploadService s3UploadService;
    @Mock
    private BadReviewTaskService badReviewTaskService;
    @Mock
    private ReviewRecoveryTaskService reviewRecoveryTaskService;
    @Mock
    private ReputationSingleReviewDraftService reputationSingleReviewDraftService;
    @Mock
    private UserService userService;
    @Mock
    private ManagerBoardEditAssembler managerBoardEditAssembler;
    @Mock
    private ManagerPermissionService managerPermissionService;
    @Mock
    private ManagerAccessService managerAccessService;
    @Mock
    private WorkerActivityService workerActivityService;
    @Mock
    private WorkerCredentialPreparationService credentialPreparationService;
    @Mock
    private ExternalReviewCheckService externalReviewCheckService;
    @Mock
    private CredentialRevealService credentialRevealService;

    private ApiManagerReviewController controller;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        controller = new ApiManagerReviewController(
                companyService,
                orderService,
                productService,
                reviewService,
                autoTextService,
                s3UploadService,
                badReviewTaskService,
                reviewRecoveryTaskService,
                reputationSingleReviewDraftService,
                userService,
                managerBoardEditAssembler,
                managerPermissionService,
                managerAccessService,
                workerActivityService,
                credentialPreparationService,
                externalReviewCheckService,
                credentialRevealService
        );
        authentication = new UsernamePasswordAuthenticationToken(
                "manager",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))
        );
    }

    @Test
    void revealsReviewCredentialOnlyAfterOrderAndReviewChecksAndDisablesCaching() {
        ReviewDTO relation = reviewRelation(ORDER_ID);
        Review review = Review.builder().id(REVIEW_ID).build();
        CredentialRevealRequest request = request("login");
        CredentialRevealResponse reveal = new CredentialRevealResponse("review-login");
        when(reviewService.getReviewDTOById(REVIEW_ID)).thenReturn(relation);
        when(reviewService.getReviewById(REVIEW_ID)).thenReturn(review);
        when(credentialRevealService.revealReview(review, request)).thenReturn(reveal);

        ResponseEntity<CredentialRevealResponse> response = controller.revealOrderReviewCredential(
                ORDER_ID,
                REVIEW_ID,
                request,
                authentication
        );

        assertNoStore(response);
        assertEquals("review-login", response.getBody().value());
        verify(managerAccessService).requireOrderAccess(ORDER_ID, authentication);
        verify(reviewService).getReviewDTOById(REVIEW_ID);
        verify(credentialRevealService).revealReview(review, request);
    }

    @Test
    void revealsBadTaskCredentialOnlyAfterOrderAndTaskChecksAndDisablesCaching() {
        BadReviewTask task = BadReviewTask.builder().id(BAD_TASK_ID).build();
        CredentialRevealRequest request = request("password");
        CredentialRevealResponse reveal = new CredentialRevealResponse("bad-task-password");
        when(badReviewTaskService.getTasksByOrderId(ORDER_ID)).thenReturn(List.of(task));
        when(badReviewTaskService.getTask(BAD_TASK_ID)).thenReturn(task);
        when(credentialRevealService.revealBadReviewTask(task, request)).thenReturn(reveal);

        ResponseEntity<CredentialRevealResponse> response = controller.revealBadReviewTaskCredential(
                ORDER_ID,
                BAD_TASK_ID,
                request,
                authentication
        );

        assertNoStore(response);
        assertSame(reveal, response.getBody());
        verify(managerAccessService).requireOrderAccess(ORDER_ID, authentication);
        verify(badReviewTaskService).getTasksByOrderId(ORDER_ID);
        verify(credentialRevealService).revealBadReviewTask(task, request);
    }

    @Test
    void revealsRecoveryTaskCredentialOnlyAfterOrderAndTaskChecksAndDisablesCaching() {
        ReviewRecoveryTask task = ReviewRecoveryTask.builder().id(RECOVERY_TASK_ID).build();
        CredentialRevealRequest request = request("login");
        CredentialRevealResponse reveal = new CredentialRevealResponse("recovery-login");
        when(reviewRecoveryTaskService.taskBelongsToOrder(RECOVERY_TASK_ID, ORDER_ID)).thenReturn(true);
        when(reviewRecoveryTaskService.getTask(RECOVERY_TASK_ID)).thenReturn(task);
        when(credentialRevealService.revealRecoveryTask(task, request)).thenReturn(reveal);

        ResponseEntity<CredentialRevealResponse> response = controller.revealRecoveryTaskCredential(
                ORDER_ID,
                RECOVERY_TASK_ID,
                request,
                authentication
        );

        assertNoStore(response);
        assertSame(reveal, response.getBody());
        verify(managerAccessService).requireOrderAccess(ORDER_ID, authentication);
        verify(reviewRecoveryTaskService).taskBelongsToOrder(RECOVERY_TASK_ID, ORDER_ID);
        verify(credentialRevealService).revealRecoveryTask(task, request);
    }

    @Test
    void deniesEveryRevealBeforeLookingUpRelationOrCredentialWhenManagerLacksOrderAccess() {
        AccessDeniedException denial = new AccessDeniedException("forbidden");
        org.mockito.Mockito.doThrow(denial)
                .when(managerAccessService)
                .requireOrderAccess(ORDER_ID, authentication);

        assertSame(denial, assertThrows(
                AccessDeniedException.class,
                () -> controller.revealOrderReviewCredential(ORDER_ID, REVIEW_ID, request("login"), authentication)
        ));
        assertSame(denial, assertThrows(
                AccessDeniedException.class,
                () -> controller.revealBadReviewTaskCredential(ORDER_ID, BAD_TASK_ID, request("login"), authentication)
        ));
        assertSame(denial, assertThrows(
                AccessDeniedException.class,
                () -> controller.revealRecoveryTaskCredential(ORDER_ID, RECOVERY_TASK_ID, request("login"), authentication)
        ));

        verify(reviewService, never()).getReviewDTOById(any());
        verify(badReviewTaskService, never()).getTasksByOrderId(any());
        verify(reviewRecoveryTaskService, never()).taskBelongsToOrder(any(), any());
        verifyNoInteractions(credentialRevealService);
    }

    @Test
    void hidesEveryCredentialWhenResourceDoesNotBelongToRequestedOrder() {
        when(reviewService.getReviewDTOById(REVIEW_ID)).thenReturn(reviewRelation(ORDER_ID + 1));
        when(badReviewTaskService.getTasksByOrderId(ORDER_ID)).thenReturn(List.of());
        when(reviewRecoveryTaskService.taskBelongsToOrder(RECOVERY_TASK_ID, ORDER_ID)).thenReturn(false);

        assertNotFound(assertThrows(
                ResponseStatusException.class,
                () -> controller.revealOrderReviewCredential(ORDER_ID, REVIEW_ID, request("login"), authentication)
        ));
        assertNotFound(assertThrows(
                ResponseStatusException.class,
                () -> controller.revealBadReviewTaskCredential(ORDER_ID, BAD_TASK_ID, request("password"), authentication)
        ));
        assertNotFound(assertThrows(
                ResponseStatusException.class,
                () -> controller.revealRecoveryTaskCredential(ORDER_ID, RECOVERY_TASK_ID, request("login"), authentication)
        ));

        verify(credentialRevealService, never()).revealReview(any(), any());
        verify(credentialRevealService, never()).revealBadReviewTask(any(), any());
        verify(credentialRevealService, never()).revealRecoveryTask(any(), any());
    }

    private ReviewDTO reviewRelation(Long orderId) {
        return ReviewDTO.builder()
                .id(REVIEW_ID)
                .orderDetails(OrderDetailsDTO.builder()
                        .order(OrderDTO.builder().id(orderId).build())
                        .build())
                .build();
    }

    private CredentialRevealRequest request(String field) {
        return new CredentialRevealRequest(field, "order-details", "manager", "review");
    }

    private void assertNoStore(ResponseEntity<CredentialRevealResponse> response) {
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertEquals("no-cache", response.getHeaders().getFirst(HttpHeaders.PRAGMA));
    }

    private void assertNotFound(ResponseStatusException exception) {
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }
}
