package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.manager.dto.api.ManagerOverdueOrdersResponse;
import com.hunt.otziv.metric_snapshots.service.UserMetricSnapshotService;
import com.hunt.otziv.p_products.board.OrderBoardQueryService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.worker_flow.service.WorkerFlowLockService;
import com.hunt.otziv.p_products.worker_access.service.WorkerCellularAccessService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.p_products.worker_flow.service.WorkerPublicationGateService;
import com.hunt.otziv.p_products.worker_flow.service.WorkerPublicationSessionService;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import com.hunt.otziv.worker_activity.dto.WorkerCredentialPreparationResponse;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import com.hunt.otziv.worker_activity.model.WorkerCredentialPreparationScope;
import com.hunt.otziv.worker_activity.service.WorkerCredentialPreparationService;
import com.hunt.otziv.worker_performance.service.StaffDailyProgressService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiWorkerBoardControllerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private OrderBoardQueryService orderBoardQueryService;

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
    private ReviewRecoveryTaskService reviewRecoveryTaskService;

    @Mock
    private UserMetricSnapshotService metricSnapshotService;

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private WorkerFlowLockService workerFlowLockService;

    @Mock
    private WorkerPublicationSessionService publicationSessionService;

    @Mock
    private WorkerActivityService workerActivityService;

    @Mock
    private WorkerCredentialPreparationService credentialPreparationService;

    @Mock
    private StaffDailyProgressService staffDailyProgressService;

    @Mock
    private WorkerCellularAccessService workerCellularAccessService;

    @Mock
    private WorkerAssignmentMutationGuardService assignmentMutationGuardService;

    @Mock
    private ScheduledClientMessageService scheduledClientMessageService;

    @Mock
    private com.hunt.otziv.worker_activity.service.WorkerRiskAccessPolicy workerRiskAccessPolicy;

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
                orderBoardQueryService,
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
                reviewRecoveryTaskService,
                metricSnapshotService,
                appSettingService,
                new WorkerPublicationGateService(
                        orderService,
                        badReviewTaskService,
                        reviewRecoveryTaskService,
                        userService,
                        workerService,
                        workerFlowLockService,
                        appSettingService,
                        publicationSessionService
                ),
                workerActivityService,
                credentialPreparationService,
                staffDailyProgressService,
                workerCellularAccessService,
                assignmentMutationGuardService,
                scheduledClientMessageService,
                workerRiskAccessPolicy
        );

        lenient().when(userService.findByUserName("worker")).thenReturn(Optional.of(user));
        lenient().when(workerRiskAccessPolicy.status("worker"))
                .thenReturn(com.hunt.otziv.worker_activity.service.WorkerRiskAccessPolicy.Status.allowed());
        lenient().when(workerService.getWorkerByUserId(77L)).thenReturn(worker);
        lenient().when(publicationSessionService.evaluateEntry(any(Worker.class), anyBoolean())).thenReturn(
                WorkerPublicationSessionService.SessionDecision.allowed(
                        WorkerPublicationSessionService.SessionState.disabled()
                )
        );
        lenient().when(publicationSessionService.currentState(any(Worker.class))).thenReturn(
                WorkerPublicationSessionService.SessionState.disabled()
        );
        lenient().when(credentialPreparationService.blockUntilReady(any(), any(), any(), any(), anyInt()))
                .thenReturn(Optional.empty());
        lenient().when(orderService.countActionableOrdersByStatusToWorker(worker)).thenReturn(Map.of());
        lenient().when(orderService.countOrdersByStatusToWorker(worker)).thenReturn(Map.of());
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
        lenient().when(reviewRecoveryTaskService.countDueTasksToWorker(eq(worker), any(LocalDate.class))).thenReturn(0);
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
        lenient().when(orderBoardQueryService.getWorkerBoardOrderDTOAndKeywordByWorkerAll(
                eq(principal),
                eq(""),
                eq(0),
                eq(10),
                eq("desc")
        )).thenReturn(emptyOrderPage());
        lenient().when(orderBoardQueryService.getWorkerBoardOrderDTOAndKeywordByWorkerAll(
                eq(principal),
                eq(""),
                eq(0),
                eq(10),
                eq("asc")
        )).thenReturn(emptyOrderPage());
        lenient().when(orderBoardQueryService.getAllOrderDTOAndKeywordByWorker(
                any(Worker.class),
                eq(""),
                anyString(),
                eq(0),
                eq(10),
                eq("desc")
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
        lenient().when(reviewRecoveryTaskService.getDueTasksToWorker(
                eq(worker),
                any(LocalDate.class),
                eq(""),
                any(Pageable.class)
        )).thenReturn(Page.empty());
    }

    @Test
    void workerCanSubmitOwnWaitingOrderForClientReview() throws Exception {
        Order order = new Order();
        order.setId(32L);
        order.setWorker(worker);
        order.setWaitingForClient(true);
        when(orderService.getOrder(32L)).thenReturn(order);
        when(orderService.changeStatusForOrder(32L, "В проверку")).thenReturn(true);

        controller.updateOrderStatus(
                32L,
                new ApiWorkerBoardController.StatusChangeRequest("В проверку"),
                mock(HttpServletRequest.class),
                principal,
                workerAuth
        );

        verify(orderService).changeStatusForOrder(32L, "В проверку");
        assertFalse(order.isWaitingForClient());
        verify(orderService).save(order);
    }

    @Test
    void enablingClientWaitingImmediatelySynchronizesReminderCycle() {
        Order order = new Order();
        order.setId(25_442L);
        order.setStatus(OrderStatus.builder().title("Новый").build());
        order.setWaitingForClient(false);
        when(orderService.getOrder(25_442L)).thenReturn(order);

        controller.updateOrderClientWaiting(
                25_442L,
                new ApiWorkerBoardController.ClientWaitingRequest(true)
        );

        assertTrue(order.isWaitingForClient());
        assertTrue(order.isClientTextExpected());
        assertTrue(order.getWaitingForClientChangedAt() != null);
        verify(assignmentMutationGuardService).assertOrder(25_442L);
        verify(orderService).save(order);
        verify(scheduledClientMessageService).synchronizeClientTextReminderForOrder(order);
    }

    @Test
    void workerCannotChangeStatusOfNonWaitingOrder() throws Exception {
        Order order = new Order();
        order.setId(32L);
        order.setWorker(worker);
        order.setWaitingForClient(false);
        when(orderService.getOrder(32L)).thenReturn(order);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> controller.updateOrderStatus(
                        32L,
                        new ApiWorkerBoardController.StatusChangeRequest("В проверку"),
                        mock(HttpServletRequest.class),
                        principal,
                        workerAuth
                )
        );

        assertEquals(403, error.getStatusCode().value());
        verify(orderService, never()).changeStatusForOrder(any(), anyString());
    }

    @Test
    void workerOverdueReminderUsesOnlySectionItemsOlderThanThreshold() {
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.minusDays(5);
        when(orderRepository.summarizeOverdueOrdersByWorker(eq(worker), eq(cutoff), anySet()))
                .thenReturn(List.of(
                        new Object[] { "Новый", 2L, today.minusDays(8) },
                        new Object[] { "Не оплачено", 5L, today.minusDays(30) }
                ));
        when(reviewService.getAllReviewDTOByWorkerByPublishToVigul(
                eq(cutoff),
                eq(principal),
                eq(0),
                eq(1),
                eq("asc"),
                eq("")
        )).thenReturn(reviewPageWithOldestDate(today.minusDays(6), 3));
        when(reviewService.getAllReviewDTOByWorkerByPublish(
                eq(cutoff),
                eq(principal),
                eq(0),
                eq(1),
                eq("asc"),
                eq("")
        )).thenReturn(reviewPageWithOldestDate(today.minusDays(5), 4));

        ReviewRecoveryTask recoveryTask = new ReviewRecoveryTask();
        recoveryTask.setScheduledDate(today.minusDays(7));
        when(reviewRecoveryTaskService.getDueTasksToWorker(
                eq(worker),
                eq(cutoff),
                eq(""),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(recoveryTask), PageRequest.of(0, 1), 1));

        BadReviewTask badTask = new BadReviewTask();
        badTask.setScheduledDate(today.minusDays(9));
        when(badReviewTaskService.getDueTasksToWorker(
                eq(worker),
                eq(cutoff),
                eq(""),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(badTask), PageRequest.of(0, 1), 1));

        ManagerOverdueOrdersResponse response = controller.getOverdueOrders(principal, workerAuth);

        assertEquals(4, response.thresholdDays());
        assertEquals(11, response.total());
        assertTrue(hasOverdueStatus(response, "Новые", 2, 8));
        assertTrue(hasOverdueStatus(response, "Выгул", 3, 6));
        assertTrue(hasOverdueStatus(response, "Публикация", 4, 5));
        assertTrue(hasOverdueStatus(response, "Восстановление", 1, 7));
        assertTrue(hasOverdueStatus(response, "Плохие", 1, 9));
        assertFalse(response.statuses().stream().anyMatch(status -> "Не оплачено".equals(status.status())));
        verify(appSettingService, never()).getInt(AppSettingService.NAGUL_LOOKAHEAD_DAYS, 60);
    }

    @Test
    void privilegedRolesUseTheSameOverdueCutoffForEveryWorkerSection() {
        LocalDate cutoff = LocalDate.now().minusDays(5);

        Principal adminPrincipal = () -> "admin";
        Authentication adminAuth = auth("ROLE_ADMIN");
        when(reviewService.getAllReviewDTOAndDateToAdminToVigul(cutoff, 0, 1, "asc", ""))
                .thenReturn(emptyReviewPage());
        when(reviewService.getAllReviewDTOAndDateToAdmin(cutoff, 0, 1, "asc", ""))
                .thenReturn(emptyReviewPage());
        when(reviewRecoveryTaskService.getDueTasksToAdmin(eq(cutoff), eq(""), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(badReviewTaskService.getDueTasksToAdmin(eq(cutoff), eq(""), any(Pageable.class)))
                .thenReturn(emptyBadTaskPage());

        assertEquals(0, controller.getOverdueOrders(adminPrincipal, adminAuth).total());

        Principal ownerPrincipal = () -> "owner";
        Authentication ownerAuth = auth("ROLE_OWNER");
        Manager ownerManager = new Manager();
        ownerManager.setId(31L);
        Set<Manager> ownerManagers = Set.of(ownerManager);
        when(userService.findManagersByUserName("owner")).thenReturn(ownerManagers);
        when(reviewService.getAllReviewDTOByOwnerByPublishToVigul(cutoff, ownerPrincipal, 0, 1, "asc", ""))
                .thenReturn(emptyReviewPage());
        when(reviewService.getAllReviewDTOByOwnerByPublish(cutoff, ownerPrincipal, 0, 1, "asc", ""))
                .thenReturn(emptyReviewPage());
        when(reviewRecoveryTaskService.getDueTasksToOwner(eq(ownerManagers), eq(cutoff), eq(""), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(badReviewTaskService.getDueTasksToOwner(eq(ownerManagers), eq(cutoff), eq(""), any(Pageable.class)))
                .thenReturn(emptyBadTaskPage());

        assertEquals(0, controller.getOverdueOrders(ownerPrincipal, ownerAuth).total());

        Principal managerPrincipal = () -> "manager-overdue";
        Authentication managerAuth = auth("ROLE_MANAGER");
        User managerUser = new User();
        managerUser.setId(41L);
        Manager manager = new Manager();
        manager.setId(42L);
        when(userService.findByUserName("manager-overdue")).thenReturn(Optional.of(managerUser));
        when(managerService.getManagerByUserId(41L)).thenReturn(manager);
        when(reviewService.getAllReviewDTOByManagerByPublishToVigul(cutoff, managerPrincipal, 0, 1, "asc", ""))
                .thenReturn(emptyReviewPage());
        when(reviewService.getAllReviewDTOByManagerByPublish(cutoff, managerPrincipal, 0, 1, "asc", ""))
                .thenReturn(emptyReviewPage());
        when(reviewRecoveryTaskService.getDueTasksToManager(eq(manager), eq(cutoff), eq(""), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(badReviewTaskService.getDueTasksToManager(eq(manager), eq(cutoff), eq(""), any(Pageable.class)))
                .thenReturn(emptyBadTaskPage());

        assertEquals(0, controller.getOverdueOrders(managerPrincipal, managerAuth).total());
    }

    @Test
    void managerWorkerFilterShowsOnlyManagerWorkersAndRejectsOthers() {
        Principal managerPrincipal = () -> "manager";
        Authentication managerAuth = auth("ROLE_MANAGER");
        User managerUser = new User();
        managerUser.setId(11L);
        Manager manager = new Manager();
        manager.setId(22L);
        manager.setUser(managerUser);
        Worker ownWorker = workerOption(101L, "Анна Специалист");

        when(userService.findByUserName("manager")).thenReturn(Optional.of(managerUser));
        when(managerService.getManagerByUserId(11L)).thenReturn(manager);
        when(workerService.getAllWorkersToManager(manager)).thenReturn(List.of(ownWorker));

        ApiWorkerBoardController.WorkerBoardResponse response = controller.getBoard(
                "new", "", 0, 10, "desc", 101L, managerPrincipal, managerAuth);

        assertEquals(101L, response.selectedWorkerId());
        assertTrue(response.workerFilterAvailable());
        assertEquals(1, response.workerOptions().size());
        assertEquals("Анна Специалист", response.workerOptions().getFirst().label());

        assertThrows(ResponseStatusException.class, () -> controller.getBoard(
                "new", "", 0, 10, "desc", 202L, managerPrincipal, managerAuth));
    }

    @Test
    void ownerWorkerFilterUsesWorkersFromOwnerManagers() {
        Principal ownerPrincipal = () -> "owner";
        Authentication ownerAuth = auth("ROLE_OWNER");
        Manager manager = new Manager();
        manager.setId(33L);
        Worker worker = workerOption(303L, "Олег Специалист");

        when(userService.findManagersByUserName("owner")).thenReturn(Set.of(manager));
        when(workerService.getAllWorkersToManagerList(anyList())).thenReturn(Set.of(worker));
        when(orderService.getAllOrderDTOAndKeywordByOwner(
                eq(ownerPrincipal),
                eq(""),
                eq("Новый"),
                eq(0),
                eq(10),
                eq("desc")
        )).thenReturn(emptyOrderPage());

        ApiWorkerBoardController.WorkerBoardResponse response = controller.getBoard(
                "new", "", 0, 10, "desc", null, ownerPrincipal, ownerAuth);

        assertTrue(response.workerFilterAvailable());
        assertEquals(1, response.workerOptions().size());
        assertEquals(303L, response.workerOptions().getFirst().id());
    }

    @Test
    void ownerAllManagersModeUsesWorkersFromEveryManager() {
        Principal ownerPrincipal = () -> "owner-all";
        Authentication ownerAuth = auth("ROLE_OWNER");
        User owner = new User();
        owner.setId(44L);
        owner.setOwnerControlViewMode(" ALL_MANAGERS ");
        Manager manager = new Manager();
        manager.setId(33L);
        Worker worker = workerOption(303L, "Олег Специалист");

        when(userService.findByUserName("owner-all")).thenReturn(Optional.of(owner));
        when(managerService.getAllManagers()).thenReturn(List.of(manager));
        when(workerService.getAllWorkersToManagerList(anyList())).thenReturn(Set.of(worker));
        when(orderService.getAllOrderDTOAndKeywordByOwner(
                eq(ownerPrincipal),
                eq(""),
                eq("Новый"),
                eq(0),
                eq(10),
                eq("desc")
        )).thenReturn(emptyOrderPage());

        ApiWorkerBoardController.WorkerBoardResponse response = controller.getBoard(
                "new", "", 0, 10, "desc", null, ownerPrincipal, ownerAuth);

        assertEquals(1, response.workerOptions().size());
        assertEquals(303L, response.workerOptions().getFirst().id());
    }

    @Test
    void adminWorkerFilterUsesAllWorkers() {
        Principal adminPrincipal = () -> "admin";
        Authentication adminAuth = auth("ROLE_ADMIN");
        when(workerService.getAllWorkers()).thenReturn(List.of(
                workerOption(401L, "Яна Специалист"),
                workerOption(402L, "Борис Специалист")
        ));
        when(orderService.getAllOrderDTOAndKeywordAndStatus("", "Новый", 0, 10, "desc"))
                .thenReturn(emptyOrderPage());

        ApiWorkerBoardController.WorkerBoardResponse response = controller.getBoard(
                "new", "", 0, 10, "desc", null, adminPrincipal, adminAuth);

        assertTrue(response.workerFilterAvailable());
        assertEquals(2, response.workerOptions().size());
    }

    @Test
    void workerCanOpenNagulWhenNewAndCorrectionHaveOrders() {
        when(orderService.countActionableOrdersByStatusToWorker(worker))
                .thenReturn(Map.of("Новый", 3, "Коррекция", 2));

        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("nagul");

        assertEquals("nagul", response.section());
        assertFalse(response.warning());
        verify(workerCellularAccessService).enforceSection("nagul");
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
        assertEquals(Sort.Direction.ASC, pageable.getSort().getOrderFor("scheduledDate").getDirection());
        assertEquals(Sort.Direction.ASC, pageable.getSort().getOrderFor("id").getDirection());
        verify(reviewService, never()).hasActiveNagulReviews(principal);
    }

    @Test
    void workerCanOpenRecoveryTasksBeforePublication() {
        when(orderService.countActionableOrdersByStatusToWorker(worker))
                .thenReturn(Map.of("Новый", 3, "Коррекция", 2));
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("recovery");

        assertEquals("recovery", response.section());
        assertFalse(response.warning());
        verify(reviewRecoveryTaskService).getDueTasksToWorker(
                eq(worker),
                any(LocalDate.class),
                eq(""),
                pageableCaptor.capture()
        );
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(Sort.Direction.ASC, pageable.getSort().getOrderFor("scheduledDate").getDirection());
        assertEquals(Sort.Direction.ASC, pageable.getSort().getOrderFor("id").getDirection());
        verify(reviewService, never()).hasActiveNagulReviews(principal);
    }

    @Test
    void recoveryTaskKeepsCompanyAndCityWhenReviewDtoFallsBackAfterBotChange() {
        when(orderService.countActionableOrdersByStatusToWorker(worker))
                .thenReturn(Map.of("Новый", 3, "Коррекция", 2));

        City city = new City();
        city.setTitle("Иркутск");
        Company company = new Company();
        company.setId(321L);
        company.setTitle("Well Event");
        company.setCity("Иркутск");
        company.setCommentsCompany("заметка компании");
        Filial filial = new Filial();
        filial.setTitle("ЛЧ");
        filial.setUrl("https://example.test/filial");
        filial.setCity(city);

        Order order = new Order();
        order.setId(654L);
        order.setCompany(company);
        order.setZametka("заметка заказа");

        Review review = new Review();
        review.setId(164388L);
        review.setFilial(filial);

        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .id(99L)
                .order(order)
                .sourceReview(review)
                .status(ReviewRecoveryTaskStatus.PLANNED)
                .recoveryText("Текст восстановления")
                .scheduledDate(LocalDate.now())
                .build();
        ReviewDTOOne fallbackReview = ReviewDTOOne.builder()
                .id(164388L)
                .companyTitle("ОШИБКА ПРИ ОБРАБОТКЕ")
                .text("Не удалось загрузить данные отзыва")
                .build();

        when(reviewRecoveryTaskService.getDueTasksToWorker(
                eq(worker),
                any(LocalDate.class),
                eq(""),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(task), PageRequest.of(0, 10), 1));
        when(reviewService.toReviewDTOOne(review)).thenReturn(fallbackReview);

        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("recovery");
        ApiWorkerBoardController.WorkerReviewResponse item = response.reviews().content().getFirst();

        assertEquals("Well Event", item.companyTitle());
        assertEquals("Иркутск", item.filialCity());
        assertEquals(321L, item.companyId());
        assertEquals(654L, item.orderId());
        assertEquals("заметка компании", item.commentCompany());
        assertEquals("заметка заказа", item.orderComments());
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
        verify(orderBoardQueryService).getAllOrderDTOAndKeywordByWorker(worker, "", "Новый", 0, 10, "desc");
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
        verify(orderBoardQueryService).getAllOrderDTOAndKeywordByWorker(worker, "", "Коррекция", 0, 10, "desc");
        verify(reviewService, never()).hasActiveNagulReviews(principal);
    }

    @Test
    void workerPublishRedirectsToRecoveryWhenRecoveryTaskIsOverdueMoreThanTwoDays() {
        when(appSettingService.getBoolean(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ENABLED, false))
                .thenReturn(true);
        when(reviewRecoveryTaskService.countDueTasksToWorker(eq(worker), eq(LocalDate.now())))
                .thenReturn(1);
        when(reviewRecoveryTaskService.countDueTasksToWorker(eq(worker), eq(LocalDate.now().minusDays(3))))
                .thenReturn(1);
        when(workerFlowLockService.syncPublicationLock("worker:88", 88L, false, false))
                .thenReturn(false);
        when(workerFlowLockService.syncPublicationLock("worker:88:special-tasks", 88L, true, true))
                .thenReturn(true);

        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("publish");

        assertEquals("recovery", response.section());
        assertTrue(response.warning());
        assertTrue(response.message().contains("Восстановление"));
        assertTrue(response.message().contains("больше 2 дней"));
        verify(reviewRecoveryTaskService).getDueTasksToWorker(eq(worker), any(LocalDate.class), eq(""), any(Pageable.class));
        verify(reviewService, never()).getAllReviewDTOByWorkerByPublish(
                any(LocalDate.class),
                eq(principal),
                eq(0),
                eq(10),
                eq("desc"),
                eq("")
        );
    }

    @Test
    void workerPublishRedirectsToBadWhenBadTaskIsOverdueMoreThanTwoDays() {
        when(appSettingService.getBoolean(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ENABLED, false))
                .thenReturn(true);
        when(badReviewTaskService.countDueTasksToWorker(eq(worker), eq(LocalDate.now())))
                .thenReturn(1);
        when(badReviewTaskService.countDueTasksToWorker(eq(worker), eq(LocalDate.now().minusDays(3))))
                .thenReturn(1);
        when(workerFlowLockService.syncPublicationLock("worker:88", 88L, false, false))
                .thenReturn(false);
        when(workerFlowLockService.syncPublicationLock("worker:88:special-tasks", 88L, true, true))
                .thenReturn(true);

        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("publish");

        assertEquals("bad", response.section());
        assertTrue(response.warning());
        assertTrue(response.message().contains("Плохие"));
        assertTrue(response.message().contains("больше 2 дней"));
        verify(badReviewTaskService).getDueTasksToWorker(eq(worker), any(LocalDate.class), eq(""), any(Pageable.class));
        verify(reviewService, never()).getAllReviewDTOByWorkerByPublish(
                any(LocalDate.class),
                eq(principal),
                eq(0),
                eq(10),
                eq("desc"),
                eq("")
        );
    }

    @Test
    void workerPublishActionIsRejectedWhenBadTaskIsOverdueMoreThanTwoDays() throws Exception {
        when(appSettingService.getBoolean(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ENABLED, false))
                .thenReturn(true);
        when(badReviewTaskService.countDueTasksToWorker(eq(worker), eq(LocalDate.now())))
                .thenReturn(1);
        when(badReviewTaskService.countDueTasksToWorker(eq(worker), eq(LocalDate.now().minusDays(3))))
                .thenReturn(1);
        when(workerFlowLockService.syncPublicationLock("worker:88", 88L, false, false))
                .thenReturn(false);
        when(workerFlowLockService.syncPublicationLock("worker:88:special-tasks", 88L, true, true))
                .thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.publishReview(15L, principal, workerAuth)
        );

        assertEquals(409, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("Плохие"));
        verify(orderService, never()).changeStatusAndOrderCounter(15L);
    }

    @Test
    void workerPublishActionIsRejectedWhenCredentialPreparationIsNotReady() throws Exception {
        Review review = new Review();
        review.setId(15L);
        when(reviewService.getReviewById(15L)).thenReturn(review);
        when(credentialPreparationService.blockUntilReady(
                eq(workerAuth),
                eq(WorkerCredentialPreparationScope.PUBLISH),
                eq(15L),
                eq(null),
                eq(150)
        )).thenReturn(Optional.of(new WorkerCredentialPreparationService.CredentialPreparationBlock(
                "После копирования логина и пароля подождите еще 1 сек.",
                1
        )));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.publishReview(15L, principal, workerAuth)
        );

        assertEquals(409, exception.getStatusCode().value());
        assertEquals("После копирования логина и пароля подождите еще 1 сек.", exception.getReason());
        verify(orderService, never()).changeStatusAndOrderCounter(15L);
    }

    @Test
    void workerPublishActionAllowsTasksOverdueExactlyTwoDays() throws Exception {
        when(appSettingService.getBoolean(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ENABLED, false))
                .thenReturn(true);
        when(badReviewTaskService.countDueTasksToWorker(eq(worker), eq(LocalDate.now())))
                .thenReturn(1);
        when(badReviewTaskService.countDueTasksToWorker(eq(worker), eq(LocalDate.now().minusDays(3))))
                .thenReturn(0);
        when(reviewRecoveryTaskService.countDueTasksToWorker(eq(worker), eq(LocalDate.now().minusDays(3))))
                .thenReturn(0);
        when(workerFlowLockService.syncPublicationLock("worker:88", 88L, false, false))
                .thenReturn(false);
        when(workerFlowLockService.syncPublicationLock("worker:88:special-tasks", 88L, true, false))
                .thenReturn(false);
        when(orderService.changeStatusAndOrderCounter(15L)).thenReturn(true);

        controller.publishReview(15L, principal, workerAuth);

        verify(workerCellularAccessService).enforceProtectedAccess("publish");
        verify(orderService).changeStatusAndOrderCounter(15L);
    }

    @Test
    void correctionMetricIncludesOrderWaitingForClient() {
        when(orderService.countOrdersByStatusToWorker(worker))
                .thenReturn(Map.of("Коррекция", 1));
        when(orderService.countActionableOrdersByStatusToWorker(worker))
                .thenReturn(Map.of());

        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("correct");

        ApiWorkerBoardController.WorkerMetricResponse correction = response.metrics().stream()
                .filter(metric -> "correct".equals(metric.section()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, correction.value());
        verify(orderService).countOrdersByStatusToWorker(worker);
    }

    @Test
    void genericReviewActionUsesUnprotectedNewSourceSection() {
        Review review = new Review();
        review.setId(15L);
        Order order = new Order();
        order.setStatus(OrderStatus.builder().title("Новый").build());
        OrderDetails details = new OrderDetails();
        details.setOrder(order);
        review.setOrderDetails(details);
        when(reviewService.getReviewById(15L)).thenReturn(review);

        controller.changeReviewBot(
                15L,
                new ApiWorkerBoardController.WorkerActivitySourceRequest("worker-board", null, "new"),
                principal,
                workerAuth
        );

        verify(workerCellularAccessService).enforceSection("new");
        verify(workerCellularAccessService, never()).enforceProtectedAccess("review");
    }

    @Test
    void genericReviewActionUsesProtectedSourceSection() {
        Review review = new Review();
        review.setId(15L);
        when(reviewService.getReviewById(15L)).thenReturn(review);

        controller.changeReviewBot(
                15L,
                new ApiWorkerBoardController.WorkerActivitySourceRequest("worker-board", null, "nagul"),
                principal,
                workerAuth
        );

        verify(workerCellularAccessService).enforceSection("nagul");
        verify(workerCellularAccessService, never()).enforceProtectedAccess("review");
    }

    @Test
    void genericReviewActionIgnoresSpoofedNewSectionForProtectedReview() {
        Review review = new Review();
        review.setId(15L);
        review.setVigul(true);
        when(reviewService.getReviewById(15L)).thenReturn(review);

        controller.changeReviewBot(
                15L,
                new ApiWorkerBoardController.WorkerActivitySourceRequest("worker-board", null, "new"),
                principal,
                workerAuth
        );

        verify(workerCellularAccessService).enforceSection("publish");
    }

    @Test
    void legacyReviewActionFallsBackToReviewState() {
        Review review = new Review();
        review.setId(15L);
        review.setVigul(true);
        when(reviewService.getReviewById(15L)).thenReturn(review);

        controller.changeReviewBot(15L, null, principal, workerAuth);

        verify(workerCellularAccessService).enforceSection("publish");
    }

    @Test
    void workerNagulActionIsRejectedWhenCredentialPreparationIsNotReady() {
        Review review = new Review();
        review.setId(15L);
        when(reviewService.getReviewById(15L)).thenReturn(review);
        when(credentialPreparationService.blockUntilReady(
                eq(workerAuth),
                eq(WorkerCredentialPreparationScope.NAGUL),
                eq(15L),
                eq(null),
                eq(180)
        )).thenReturn(Optional.of(new WorkerCredentialPreparationService.CredentialPreparationBlock(
                "После копирования логина и пароля подождите еще 2 сек.",
                2
        )));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.nagulReview(15L, principal, workerAuth)
        );

        assertEquals(409, exception.getStatusCode().value());
        assertEquals("После копирования логина и пароля подождите еще 2 сек.", exception.getReason());
        verify(reviewService, never()).performNagulWithExceptions(15L, "worker");
    }

    @Test
    void updateReviewBotNameTrimsAndSavesAssignedBot() {
        Bot bot = new Bot();
        bot.setId(31L);
        bot.setFio("Old Name");
        Review review = new Review();
        review.setId(15L);
        review.setBot(bot);
        when(reviewService.getReviewById(15L)).thenReturn(review);

        controller.updateReviewBotName(
                15L,
                new ApiWorkerBoardController.ReviewBotNameUpdateRequest("  New Name  ")
        );

        assertEquals("New Name", bot.getFio());
        verify(botService).save(bot);
    }

    @Test
    void updateReviewBotNameRejectsBlankValue() {
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> controller.updateReviewBotName(
                        15L,
                        new ApiWorkerBoardController.ReviewBotNameUpdateRequest("   ")
                )
        );

        assertEquals(400, error.getStatusCode().value());
        verify(botService, never()).save(any());
    }

    @Test
    void workerCannotChangeBadTaskScheduledDate() {
        LocalDate currentDate = LocalDate.now();
        BadReviewTask task = BadReviewTask.builder()
                .scheduledDate(currentDate)
                .build();
        when(badReviewTaskService.getTask(15L)).thenReturn(task);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.updateBadReviewTask(
                        15L,
                        new ApiWorkerBoardController.BadTaskUpdateRequest("text", currentDate.plusDays(1)),
                        workerAuth
                )
        );

        assertEquals(403, exception.getStatusCode().value());
        verify(badReviewTaskService, never()).updateTask(eq(15L), anyString(), any());
    }

    @Test
    void workerCanSaveBadTaskWithCurrentScheduledDate() {
        LocalDate currentDate = LocalDate.now();
        BadReviewTask task = BadReviewTask.builder()
                .scheduledDate(currentDate)
                .build();
        when(badReviewTaskService.getTask(15L)).thenReturn(task);

        controller.updateBadReviewTask(
                15L,
                new ApiWorkerBoardController.BadTaskUpdateRequest("text", currentDate),
                workerAuth
        );

        verify(badReviewTaskService).updateTask(15L, "text", currentDate);
    }

    @Test
    void managerCanChangeBadTaskScheduledDate() {
        Authentication managerAuth = new UsernamePasswordAuthenticationToken(
                "manager",
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))
        );
        LocalDate newDate = LocalDate.now().plusDays(1);

        controller.updateBadReviewTask(
                15L,
                new ApiWorkerBoardController.BadTaskUpdateRequest("text", newDate),
                managerAuth
        );

        verify(badReviewTaskService, never()).getTask(15L);
        verify(badReviewTaskService).updateTask(15L, "text", newDate);
    }

    @Test
    void managerCanReassignBadTaskToWorkerFromOwnTeam() {
        Principal managerPrincipal = () -> "manager";
        Authentication managerAuth = auth("ROLE_MANAGER");
        User managerUser = new User();
        managerUser.setId(11L);
        Manager manager = new Manager();
        manager.setId(22L);
        Worker targetWorker = workerOption(101L, "Анна Специалист");
        Order order = new Order();
        order.setManager(manager);
        BadReviewTask task = BadReviewTask.builder().order(order).build();

        when(userService.findByUserName("manager")).thenReturn(Optional.of(managerUser));
        when(managerService.getManagerByUserId(11L)).thenReturn(manager);
        when(workerService.getAllWorkersToManager(manager)).thenReturn(List.of(targetWorker));
        when(badReviewTaskService.getTask(15L)).thenReturn(task);

        controller.reassignBadReviewTask(
                15L,
                new ApiWorkerBoardController.WorkerAssignmentRequest(101L),
                managerPrincipal,
                managerAuth
        );

        verify(badReviewTaskService).reassignTask(15L, targetWorker);
    }

    @Test
    void managerCannotReassignTaskFromAnotherManager() {
        Principal managerPrincipal = () -> "manager";
        Authentication managerAuth = auth("ROLE_MANAGER");
        User managerUser = new User();
        managerUser.setId(11L);
        Manager currentManager = new Manager();
        currentManager.setId(22L);
        Manager anotherManager = new Manager();
        anotherManager.setId(23L);
        Order order = new Order();
        order.setManager(anotherManager);
        BadReviewTask task = BadReviewTask.builder().order(order).build();

        when(userService.findByUserName("manager")).thenReturn(Optional.of(managerUser));
        when(managerService.getManagerByUserId(11L)).thenReturn(currentManager);
        when(badReviewTaskService.getTask(15L)).thenReturn(task);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.reassignBadReviewTask(
                        15L,
                        new ApiWorkerBoardController.WorkerAssignmentRequest(101L),
                        managerPrincipal,
                        managerAuth
                )
        );

        assertEquals(403, exception.getStatusCode().value());
        verify(badReviewTaskService, never()).reassignTask(any(), any());
    }

    @Test
    void staleRecoveryManagerSnapshotCannotOverrideCurrentOrderManagerOnReassign() {
        Principal managerPrincipal = () -> "old-manager";
        Authentication managerAuth = auth("ROLE_MANAGER");
        User managerUser = new User();
        managerUser.setId(11L);
        Manager oldManager = new Manager();
        oldManager.setId(22L);
        Manager currentOrderManager = new Manager();
        currentOrderManager.setId(23L);
        Order order = new Order();
        order.setManager(currentOrderManager);
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .order(order)
                .manager(oldManager)
                .build();

        when(userService.findByUserName("old-manager")).thenReturn(Optional.of(managerUser));
        when(managerService.getManagerByUserId(11L)).thenReturn(oldManager);
        when(reviewRecoveryTaskService.getTask(15L)).thenReturn(task);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.reassignRecoveryTask(
                        15L,
                        new ApiWorkerBoardController.WorkerAssignmentRequest(101L),
                        managerPrincipal,
                        managerAuth
                )
        );

        assertEquals(403, exception.getStatusCode().value());
        verify(reviewRecoveryTaskService, never()).reassignTask(any(), any());
    }

    @Test
    void workerCannotChangeRecoveryTaskScheduledDate() {
        LocalDate currentDate = LocalDate.now();
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .scheduledDate(currentDate)
                .build();
        when(reviewRecoveryTaskService.getTask(15L)).thenReturn(task);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.updateRecoveryTask(
                        15L,
                        new ApiWorkerBoardController.RecoveryTaskUpdateRequest("text", "answer", currentDate.plusDays(1)),
                        workerAuth
                )
        );

        assertEquals(403, exception.getStatusCode().value());
        verify(reviewRecoveryTaskService, never()).updateTask(eq(15L), anyString(), anyString(), any());
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
        verify(orderBoardQueryService).getWorkerBoardOrderDTOAndKeywordByWorkerAll(principal, "", 0, 10, "desc");
        verify(reviewService, never()).hasActiveNagulReviews(principal);
    }

    @Test
    void workerAllPassesSortDirectionToBoardQuery() {
        ApiWorkerBoardController.WorkerBoardResponse response = getBoard("all", "asc");

        assertEquals("all", response.section());
        verify(orderBoardQueryService).getWorkerBoardOrderDTOAndKeywordByWorkerAll(principal, "", 0, 10, "asc");
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

    @Test
    void logReviewCredentialCopyClickAcceptsLoginAndLoadsReview() {
        Review review = new Review();
        review.setId(15L);
        when(reviewService.getReviewById(15L)).thenReturn(review);

        controller.logReviewCredentialCopyClick(
                15L,
                new ApiWorkerBoardController.ReviewCopyClickRequest("login"),
                principal,
                workerAuth
        );

        verify(reviewService).getReviewById(15L);
    }

    @Test
    void mobileNagulCopyReturnsServerCredentialPreparation() {
        Bot bot = new Bot();
        bot.setId(100L);
        Review review = new Review();
        review.setId(15L);
        review.setBot(bot);
        ApiWorkerBoardController.ReviewCopyClickRequest request =
                new ApiWorkerBoardController.ReviewCopyClickRequest(
                        "password",
                        "mobile-worker-board",
                        null,
                        "nagul"
                );
        WorkerCredentialPreparationResponse expected = new WorkerCredentialPreparationResponse(
                "NAGUL",
                15L,
                100L,
                "2026-07-17T17:00:00",
                "2026-07-17T17:00:01",
                "2026-07-17T17:00:01",
                true,
                true,
                false,
                180,
                180
        );
        when(reviewService.getReviewById(15L)).thenReturn(review);
        when(credentialPreparationService.recordCopy(
                workerAuth,
                review,
                "password",
                "mobile-worker-board",
                null,
                "nagul"
        )).thenReturn(true);
        when(credentialPreparationService.active(workerAuth, WorkerCredentialPreparationScope.NAGUL))
                .thenReturn(expected);

        WorkerCredentialPreparationResponse actual = controller.logReviewCredentialCopyClick(
                15L,
                request,
                principal,
                workerAuth
        );

        assertEquals(expected, actual);
    }

    @Test
    void credentialCopyDoesNotReturnFalseSuccessForUnknownMobileSource() {
        Review review = new Review();
        review.setId(15L);
        ApiWorkerBoardController.ReviewCopyClickRequest request =
                new ApiWorkerBoardController.ReviewCopyClickRequest(
                        "login",
                        "unexpected-mobile-board",
                        null,
                        "nagul"
                );
        when(reviewService.getReviewById(15L)).thenReturn(review);
        when(credentialPreparationService.recordCopy(
                workerAuth,
                review,
                "login",
                "unexpected-mobile-board",
                null,
                "nagul"
        )).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.logReviewCredentialCopyClick(15L, request, principal, workerAuth)
        );

        assertEquals(400, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("не подтвердил подготовку аккаунта"));
    }

    @Test
    void logReviewCredentialCopyClickRejectsUnsupportedField() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.logReviewCredentialCopyClick(
                        15L,
                        new ApiWorkerBoardController.ReviewCopyClickRequest("text"),
                        principal,
                        workerAuth
                )
        );

        assertEquals("Кнопка для логирования не поддерживается", exception.getReason());
        verify(reviewService, never()).getReviewById(15L);
    }

    @Test
    void logRecoveryTaskCredentialCopyClickUsesTaskWithoutLoadingArchivedReview() {
        Bot bot = new Bot();
        bot.setId(81L);
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .id(92L)
                .archiveReviewId(143065L)
                .archiveOrderId(19362L)
                .bot(bot)
                .build();
        when(reviewRecoveryTaskService.getTask(92L)).thenReturn(task);

        controller.logRecoveryTaskCredentialCopyClick(
                92L,
                new ApiWorkerBoardController.ReviewCopyClickRequest(
                        "password",
                        "worker-board",
                        "worker-board",
                        "recovery"
                ),
                principal,
                workerAuth
        );

        verify(reviewRecoveryTaskService).getTask(92L);
        verify(reviewService, never()).getReviewById(143065L);
        verify(workerActivityService).recordSafely(
                eq(workerAuth),
                eq(WorkerActivityAction.REVIEW_COPY_PASSWORD),
                eq("recovery_task"),
                eq(92L),
                eq(19362L),
                eq(143065L),
                eq("recovery"),
                anyString()
        );
    }

    @Test
    void logRecoveryTaskCredentialCopyClickRejectsUnsupportedFieldBeforeLoadingTask() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.logRecoveryTaskCredentialCopyClick(
                        92L,
                        new ApiWorkerBoardController.ReviewCopyClickRequest("text"),
                        principal,
                        workerAuth
                )
        );

        assertEquals("Кнопка для логирования не поддерживается", exception.getReason());
        verify(reviewRecoveryTaskService, never()).getTask(92L);
    }

    @Test
    void logBadReviewTaskCredentialCopyClickUsesTaskOwnershipInsteadOfSourceReviewOwnership() {
        Bot bot = new Bot();
        bot.setId(82L);
        Review sourceReview = new Review();
        sourceReview.setId(172291L);
        BadReviewTask task = BadReviewTask.builder()
                .id(597L)
                .sourceReview(sourceReview)
                .bot(bot)
                .build();
        when(badReviewTaskService.getTask(597L)).thenReturn(task);

        controller.logBadReviewTaskCredentialCopyClick(
                597L,
                new ApiWorkerBoardController.ReviewCopyClickRequest(
                        "login",
                        "worker-board",
                        null,
                        "bad"
                ),
                principal,
                workerAuth
        );

        verify(assignmentMutationGuardService).assertBadTask(597L);
        verify(assignmentMutationGuardService, never()).assertReview(172291L);
        verify(badReviewTaskService).getTask(597L);
        verify(workerActivityService).recordSafely(
                eq(workerAuth),
                eq(WorkerActivityAction.REVIEW_COPY_LOGIN),
                eq("bad_review_task"),
                eq(597L),
                any(),
                eq(172291L),
                eq("bad"),
                anyString()
        );
    }

    @Test
    void logBadReviewTaskCredentialCopyClickRejectsUnsupportedFieldBeforeLoadingTask() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.logBadReviewTaskCredentialCopyClick(
                        597L,
                        new ApiWorkerBoardController.ReviewCopyClickRequest("text"),
                        principal,
                        workerAuth
                )
        );

        assertEquals("Кнопка для логирования не поддерживается", exception.getReason());
        verify(badReviewTaskService, never()).getTask(597L);
    }

    @Test
    void deactivateRecoveryTaskBotPreservesDomainConflictStatus() {
        ReviewRecoveryTask task = ReviewRecoveryTask.builder().id(40L).build();
        when(reviewRecoveryTaskService.getTask(40L)).thenReturn(task);
        when(reviewRecoveryTaskService.deactivateAndChangeTaskBot(40L, 99L))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "bot mismatch"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.deactivateRecoveryTaskBot(40L, 99L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("bot mismatch", exception.getReason());
    }

    @Test
    void deactivateBadReviewTaskBotPreservesDomainConflictStatus() {
        BadReviewTask task = BadReviewTask.builder().id(42L).build();
        when(badReviewTaskService.getTask(42L)).thenReturn(task);
        when(badReviewTaskService.deactivateAndChangeTaskBot(42L, 99L))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "bot mismatch"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.deactivateBadReviewTaskBot(42L, 99L)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("bot mismatch", exception.getReason());
    }

    private ApiWorkerBoardController.WorkerBoardResponse getBoard(String section) {
        return controller.getBoard(section, "", 0, 10, "desc", null, principal, workerAuth);
    }

    private ApiWorkerBoardController.WorkerBoardResponse getBoard(String section, String sortDirection) {
        return controller.getBoard(section, "", 0, 10, sortDirection, null, principal, workerAuth);
    }

    private Page<ReviewDTOOne> reviewPageWithOldestDate(LocalDate oldestDate, long total) {
        ReviewDTOOne review = ReviewDTOOne.builder()
                .id(1L)
                .publishedDate(oldestDate)
                .build();
        return new PageImpl<>(List.of(review), PageRequest.of(0, 1), total);
    }

    private boolean hasOverdueStatus(
            ManagerOverdueOrdersResponse response,
            String label,
            long count,
            long maxDays
    ) {
        return response.statuses().stream()
                .anyMatch(status -> label.equals(status.status())
                        && status.count() == count
                        && status.maxDays() == maxDays);
    }

    private Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("user", "password", List.of(new SimpleGrantedAuthority(role)));
    }

    private Worker workerOption(Long id, String fio) {
        User user = new User();
        user.setFio(fio);
        Worker option = new Worker();
        option.setId(id);
        option.setUser(user);
        return option;
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
