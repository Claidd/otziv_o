package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.metric_snapshots.service.UserMetricSnapshotService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.worker_flow.WorkerFlowLockService;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiWorkerBoardControllerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderDetailsService orderDetailsService;

    @Mock
    private ReviewService reviewService;

    @Mock
    private PromoTextService promoTextService;

    @Mock
    private BotService botService;

    @Mock
    private CompanyService companyService;

    @Mock
    private UserService userService;

    @Mock
    private ManagerService managerService;

    @Mock
    private WorkerService workerService;

    @Mock
    private BadReviewTaskService badReviewTaskService;

    @Mock
    private UserMetricSnapshotService metricSnapshotService;

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private WorkerFlowLockService workerFlowLockService;

    private ApiWorkerBoardController controller;
    private Principal principal;
    private Authentication workerAuth;
    private Worker worker;

    @BeforeEach
    void setUp() {
        principal = () -> "worker";
        workerAuth = new UsernamePasswordAuthenticationToken(
                "worker",
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_WORKER"))
        );

        User user = new User();
        user.setId(77L);
        worker = new Worker();
        worker.setId(88L);

        controller = new ApiWorkerBoardController(
                orderService,
                orderRepository,
                orderDetailsService,
                reviewService,
                promoTextService,
                botService,
                companyService,
                userService,
                managerService,
                workerService,
                new PerformanceMetrics(new SimpleMeterRegistry()),
                badReviewTaskService,
                metricSnapshotService,
                appSettingService,
                workerFlowLockService
        );

        lenient().when(userService.findByUserName("worker")).thenReturn(Optional.of(user));
        lenient().when(workerService.getWorkerByUserId(77L)).thenReturn(worker);
        lenient().when(orderService.countActionableOrdersByStatusToWorker(worker)).thenReturn(Map.of());
        lenient().when(orderService.countActionableOrdersByStatusToWorkerChangedOnOrBefore(
                eq(worker),
                anySet(),
                any(LocalDate.class)
        )).thenReturn(Map.of());
        lenient().when(reviewService.countBoardReviewMetrics(
                any(LocalDate.class),
                any(LocalDate.class),
                eq("Не оплачено"),
                eq(principal),
                eq("WORKER")
        )).thenReturn(Map.of());
        lenient().when(badReviewTaskService.countDueTasksToWorker(eq(worker), any(LocalDate.class))).thenReturn(0);
        lenient().when(metricSnapshotService.deltas(
                eq(principal),
                eq(UserMetricSnapshotService.PAGE_WORKER),
                anyList()
        )).thenReturn(Map.of());
        lenient().when(appSettingService.getInt(AppSettingService.NAGUL_LOOKAHEAD_DAYS, 60)).thenReturn(60);
        lenient().when(promoTextService.getAllPromoTexts()).thenReturn(List.of());
        lenient().when(orderService.getAllOrderDTOAndKeywordByWorker(
                eq(principal),
                eq(""),
                anyString(),
                eq(0),
                eq(10)
        )).thenReturn(emptyOrderPage());
        lenient().when(orderService.getAllOrderDTOAndKeywordByWorkerAll(
                eq(principal),
                eq(""),
                eq(0),
                eq(10)
        )).thenReturn(emptyOrderPage());
        lenient().when(reviewService.getAllReviewDTOByWorkerByPublishToVigul(
                any(LocalDate.class),
                eq(principal),
                eq(0),
                eq(10),
                eq("desc"),
                eq("")
        )).thenReturn(emptyReviewPage());
        lenient().when(reviewService.getAllReviewDTOByWorkerByPublish(
                any(LocalDate.class),
                eq(principal),
                eq(0),
                eq(10),
                eq("desc"),
                eq("")
        )).thenReturn(emptyReviewPage());
        lenient().when(badReviewTaskService.getDueTasksToWorker(
                eq(worker),
                any(LocalDate.class),
                eq(""),
                any(Pageable.class)
        )).thenReturn(emptyBadTaskPage());
    }

    @Test
    void workerCanOpenNagulWhenNewAndCorrectionHaveOrders() {
        when(orderService.countActionableOrdersByStatusToWorker(worker))
                .thenReturn(Map.of("Новый", 3, "Коррекция", 2));

        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("nagul");

        assertEquals("nagul", response.section());
        assertFalse(response.warning());
        verify(reviewService).getAllReviewDTOByWorkerByPublishToVigul(
                any(LocalDate.class),
                eq(principal),
                eq(0),
                eq(10),
                eq("desc"),
                eq("")
        );
    }

    @Test
    void workerCanOpenBadTasksWhenEarlierStepsHaveWork() {
        when(orderService.countActionableOrdersByStatusToWorker(worker))
                .thenReturn(Map.of("Новый", 3, "Коррекция", 2));
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("bad");

        assertEquals("bad", response.section());
        assertFalse(response.warning());
        verify(badReviewTaskService).getDueTasksToWorker(
                eq(worker),
                any(LocalDate.class),
                eq(""),
                pageableCaptor.capture()
        );
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("scheduledDate").getDirection());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("id").getDirection());
        verify(reviewService, never()).hasActiveNagulReviews(principal);
    }

    @Test
    void workerPublishOpensWhenNewAndCorrectionOrdersAreFresh() {
        when(orderService.countActionableOrdersByStatusToWorker(worker))
                .thenReturn(Map.of("Новый", 4, "Коррекция", 2));

        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("publish");

        assertEquals("publish", response.section());
        assertFalse(response.warning());
        verify(reviewService).getAllReviewDTOByWorkerByPublish(
                any(LocalDate.class),
                eq(principal),
                eq(0),
                eq(10),
                eq("desc"),
                eq("")
        );
        verify(reviewService, never()).hasActiveNagulReviews(principal);
    }

    @Test
    void workerPublishRedirectsToNewWhenAnyNewOrCorrectionOrderIsStale() {
        when(orderService.countActionableOrdersByStatusToWorker(worker))
                .thenReturn(Map.of("Новый", 4, "Коррекция", 2));
        when(orderService.countActionableOrdersByStatusToWorkerChangedOnOrBefore(
                eq(worker),
                anySet(),
                any(LocalDate.class)
        )).thenReturn(Map.of("Коррекция", 1));
        when(workerFlowLockService.syncPublicationLock("worker:88", 88L, true, true)).thenReturn(true);

        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("publish");

        assertEquals("new", response.section());
        assertTrue(response.warning());
        assertTrue(response.message().contains("без изменений"));
        assertTrue(response.message().contains("ждут клиента"));
        verify(orderService).getAllOrderDTOAndKeywordByWorker(principal, "", "Новый", 0, 10);
        verify(reviewService, never()).hasActiveNagulReviews(principal);
    }

    @Test
    void workerPublishRedirectsToCorrectionWhenOnlyCorrectionBlocksPublication() {
        when(orderService.countActionableOrdersByStatusToWorker(worker))
                .thenReturn(Map.of("Коррекция", 2));
        when(orderService.countActionableOrdersByStatusToWorkerChangedOnOrBefore(
                eq(worker),
                anySet(),
                any(LocalDate.class)
        )).thenReturn(Map.of("Коррекция", 1));
        when(workerFlowLockService.syncPublicationLock("worker:88", 88L, true, true)).thenReturn(true);

        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("publish");

        assertEquals("correct", response.section());
        assertTrue(response.warning());
        assertTrue(response.message().contains("Коррекции"));
        verify(orderService).getAllOrderDTOAndKeywordByWorker(principal, "", "Коррекция", 0, 10);
        verify(reviewService, never()).hasActiveNagulReviews(principal);
    }

    @Test
    void workerPublicationLockStaysUntilNewAndCorrectionAreEmpty() {
        when(orderService.countActionableOrdersByStatusToWorker(worker))
                .thenReturn(Map.of("Коррекция", 1));
        when(orderService.countActionableOrdersByStatusToWorkerChangedOnOrBefore(
                eq(worker),
                anySet(),
                any(LocalDate.class)
        )).thenReturn(Map.of("Коррекция", 1));
        when(workerFlowLockService.syncPublicationLock("worker:88", 88L, true, true)).thenReturn(true);
        when(workerFlowLockService.syncPublicationLock("worker:88", 88L, true, false)).thenReturn(true);

        ApiWorkerBoardController.WorkerBoardResponse firstResponse = getBoard("publish");

        assertEquals("correct", firstResponse.section());
        assertTrue(firstResponse.warning());

        when(orderService.countActionableOrdersByStatusToWorkerChangedOnOrBefore(
                eq(worker),
                anySet(),
                any(LocalDate.class)
        )).thenReturn(Map.of());

        ApiWorkerBoardController.WorkerBoardResponse secondResponse = getBoard("all");

        assertEquals("correct", secondResponse.section());
        assertTrue(secondResponse.warning());

        when(orderService.countActionableOrdersByStatusToWorker(worker))
                .thenReturn(Map.of());
        when(workerFlowLockService.syncPublicationLock("worker:88", 88L, false, false)).thenReturn(false);

        ApiWorkerBoardController.WorkerBoardResponse thirdResponse = getBoard("publish");

        assertEquals("publish", thirdResponse.section());
        assertFalse(thirdResponse.warning());
    }

    @Test
    void workerPublicationLockClearsAfterAnyBoardLoadSeesEmptyNewAndCorrection() {
        when(orderService.countActionableOrdersByStatusToWorker(worker))
                .thenReturn(Map.of("Коррекция", 1));
        when(orderService.countActionableOrdersByStatusToWorkerChangedOnOrBefore(
                eq(worker),
                anySet(),
                any(LocalDate.class)
        )).thenReturn(Map.of("Коррекция", 1));
        when(workerFlowLockService.syncPublicationLock("worker:88", 88L, true, true)).thenReturn(true);

        assertEquals("correct", getBoard("publish").section());

        when(orderService.countActionableOrdersByStatusToWorker(worker))
                .thenReturn(Map.of());
        when(orderService.countActionableOrdersByStatusToWorkerChangedOnOrBefore(
                eq(worker),
                anySet(),
                any(LocalDate.class)
        )).thenReturn(Map.of());

        getBoard("current");

        when(orderService.countActionableOrdersByStatusToWorker(worker))
                .thenReturn(Map.of("Новый", 1));
        when(workerFlowLockService.syncPublicationLock("worker:88", 88L, true, false)).thenReturn(false);

        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("publish");

        assertEquals("publish", response.section());
        assertFalse(response.warning());
    }

    @Test
    void workerAllOpensWhenNewAndCorrectionHaveNoStaleOrdersEvenIfNagulIsActive() {
        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("all");

        assertEquals("all", response.section());
        assertFalse(response.warning());
        verify(orderService).getAllOrderDTOAndKeywordByWorkerAll(principal, "", 0, 10);
        verify(reviewService, never()).hasActiveNagulReviews(principal);
    }

    @Test
    void currentRequestStillChoosesNearestMetricStep() {
        when(reviewService.countBoardReviewMetrics(
                any(LocalDate.class),
                any(LocalDate.class),
                eq("Не оплачено"),
                eq(principal),
                eq("WORKER")
        )).thenReturn(Map.of("nagul", 2, "publish", 5));
        when(badReviewTaskService.countDueTasksToWorker(eq(worker), any(LocalDate.class))).thenReturn(9);

        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("current");

        assertEquals("nagul", response.section());
        assertFalse(response.warning());
    }

    @Test
    void workerNagulUsesConfiguredLookaheadDays() {
        LocalDate expectedDate = LocalDate.now().plusDays(14);
        when(appSettingService.getInt(AppSettingService.NAGUL_LOOKAHEAD_DAYS, 60)).thenReturn(14);

        getBoard("nagul");

        verify(reviewService).getAllReviewDTOByWorkerByPublishToVigul(
                eq(expectedDate),
                eq(principal),
                eq(0),
                eq(10),
                eq("desc"),
                eq("")
        );
        verify(reviewService).countBoardReviewMetrics(
                eq(LocalDate.now()),
                eq(expectedDate),
                eq("Не оплачено"),
                eq(principal),
                eq("WORKER")
        );
    }

    private ApiWorkerBoardController.WorkerBoardResponse getBoard(String section) {
        return controller.getBoard(section, "", 0, 10, "desc", principal, workerAuth);
    }

    private Page<OrderDTOList> emptyOrderPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
    }

    private Page<ReviewDTOOne> emptyReviewPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
    }

    private Page<BadReviewTask> emptyBadTaskPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
    }
}
