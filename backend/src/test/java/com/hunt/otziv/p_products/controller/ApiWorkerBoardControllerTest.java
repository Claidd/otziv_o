package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.metric_snapshots.service.UserMetricSnapshotService;
import com.hunt.otziv.p_products.board.OrderBoardQueryService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.worker_flow.WorkerFlowLockService;
import com.hunt.otziv.p_products.worker_flow.WorkerPublicationGateService;
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
import com.hunt.otziv.worker_activity.WorkerActivityService;
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
    private WorkerActivityService workerActivityService;

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
                        appSettingService
                ),
                workerActivityService
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

        verify(orderService).changeStatusAndOrderCounter(15L);
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
                principal
        );

        verify(reviewService).getReviewById(15L);
    }

    @Test
    void logReviewCredentialCopyClickRejectsUnsupportedField() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.logReviewCredentialCopyClick(
                        15L,
                        new ApiWorkerBoardController.ReviewCopyClickRequest("text"),
                        principal
                )
        );

        assertEquals("Кнопка для логирования не поддерживается", exception.getReason());
        verify(reviewService, never()).getReviewById(15L);
    }

    private ApiWorkerBoardController.WorkerBoardResponse getBoard(String section) {
        return controller.getBoard(section, "", 0, 10, "desc", null, principal, workerAuth);
    }

    private ApiWorkerBoardController.WorkerBoardResponse getBoard(String section, String sortDirection) {
        return controller.getBoard(section, "", 0, 10, sortDirection, null, principal, workerAuth);
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
