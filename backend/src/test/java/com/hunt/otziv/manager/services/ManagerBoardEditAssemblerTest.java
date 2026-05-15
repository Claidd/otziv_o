package com.hunt.otziv.manager.services;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_cities.sevices.CityService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.manager.dto.api.OptionResponse;
import com.hunt.otziv.manager.dto.api.OrderDetailsResponse;
import com.hunt.otziv.manager.dto.api.OrderEditResponse;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderStatusDTO;
import com.hunt.otziv.p_products.deletion.OrderDeletionPolicy;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.AmountService;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerBoardEditAssemblerTest {

    @Mock
    private CategoryService categoryService;

    @Mock
    private SubCategoryService subCategoryService;

    @Mock
    private CityService cityService;

    @Mock
    private CompanyStatusService companyStatusService;

    @Mock
    private OrderService orderService;

    @Mock
    private ProductService productService;

    @Mock
    private AmountService amountService;

    @Mock
    private ReviewService reviewService;

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

    @Spy
    private ManagerPermissionService managerPermissionService = new ManagerPermissionService();

    @Spy
    private OrderDeletionPolicy orderDeletionPolicy = new OrderDeletionPolicy();

    @InjectMocks
    private ManagerBoardEditAssembler assembler;

    @Test
    void buildOrderEditResponseKeepsWorkerManagerListRestricted() {
        Authentication worker = authentication("ROLE_WORKER");
        OrderDTO order = OrderDTO.builder()
                .id(12L)
                .company(CompanyDTO.builder().id(3L).title("Company").build())
                .manager(ManagerDTO.builder().managerId(9L).build())
                .status(OrderStatusDTO.builder().title("Новый").build())
                .build();

        OrderEditResponse response = assembler.buildOrderEditResponse(order, () -> "worker", worker);

        assertEquals(List.of(new OptionResponse(9L, "Менеджер #9")), response.managers());
        assertFalse(response.canComplete());
        assertFalse(response.canDelete());
    }

    @Test
    void buildOrderEditResponseLetsManagerDeleteBeforeApprovedOrder() {
        Authentication managerAuth = authentication("ROLE_MANAGER");
        User user = User.builder()
                .id(44L)
                .fio("Manager User")
                .username("manager")
                .build();
        Manager manager = Manager.builder()
                .id(9L)
                .user(user)
                .build();
        OrderDTO order = OrderDTO.builder()
                .id(12L)
                .company(CompanyDTO.builder().id(3L).title("Company").build())
                .manager(ManagerDTO.builder().managerId(9L).user(user).build())
                .status(OrderStatusDTO.builder().title("Коррекция").build())
                .build();

        when(userService.findByUserName("manager")).thenReturn(Optional.of(user));
        when(managerService.getManagerByUserId(44L)).thenReturn(manager);

        OrderEditResponse response = assembler.buildOrderEditResponse(order, () -> "manager", managerAuth);

        assertFalse(response.canComplete());
        assertTrue(response.canDelete());
        assertEquals(List.of(new OptionResponse(9L, "Manager User")), response.managers());
    }

    @Test
    void buildOrderEditResponseHidesDeleteForManagerAfterApprovedStatus() {
        Authentication managerAuth = authentication("ROLE_MANAGER");
        User user = User.builder()
                .id(44L)
                .fio("Manager User")
                .username("manager")
                .build();
        Manager manager = Manager.builder()
                .id(9L)
                .user(user)
                .build();
        OrderDTO order = OrderDTO.builder()
                .id(12L)
                .company(CompanyDTO.builder().id(3L).title("Company").build())
                .manager(ManagerDTO.builder().managerId(9L).user(user).build())
                .status(OrderStatusDTO.builder().title("Публикация").build())
                .build();

        when(userService.findByUserName("manager")).thenReturn(Optional.of(user));
        when(managerService.getManagerByUserId(44L)).thenReturn(manager);

        OrderEditResponse response = assembler.buildOrderEditResponse(order, () -> "manager", managerAuth);

        assertFalse(response.canComplete());
        assertFalse(response.canDelete());
        assertEquals(List.of(new OptionResponse(9L, "Manager User")), response.managers());
    }

    @Test
    void buildOrderDetailsResponseCombinesReviewAndBadReviewSummary() {
        Authentication admin = authentication("ROLE_ADMIN");
        ReviewDTOOne review = ReviewDTOOne.builder()
                .id(51L)
                .companyId(7L)
                .orderId(12L)
                .companyTitle("Review Company")
                .productTitle("Review Product")
                .orderComments("review note")
                .commentCompany("company note")
                .url("https://review")
                .created(LocalDate.of(2026, 5, 1))
                .build();
        OrderDTO order = OrderDTO.builder()
                .id(12L)
                .amount(3)
                .counter(2)
                .sum(BigDecimal.valueOf(100))
                .status(OrderStatusDTO.builder().title("На проверке").build())
                .build();
        Review sourceReview = new Review();
        sourceReview.setId(51L);
        sourceReview.setText("текст опубликованного отзыва");
        BadReviewTask badTask = BadReviewTask.builder()
                .id(91L)
                .sourceReview(sourceReview)
                .taskText("самостоятельный плохой текст")
                .bot(Bot.builder()
                        .id(8L)
                        .fio("Аккаунт")
                        .login("bot-login")
                        .password("bot-password")
                        .build())
                .botFioSnapshot("Аккаунт из отзыва")
                .botLoginSnapshot("snapshot-login")
                .botPasswordSnapshot("snapshot-password")
                .status(BadReviewTaskStatus.NEW)
                .originalRating(5)
                .targetRating(2)
                .price(BigDecimal.valueOf(25))
                .build();

        when(orderService.getOrderDTO(12L)).thenReturn(order);
        when(reviewService.getReviewsAllByOrderId(12L)).thenReturn(List.of(review));
        when(badReviewTaskService.getSummaryForOrder(12L))
                .thenReturn(new BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(25), BigDecimal.ZERO));
        when(badReviewTaskService.getTasksByOrderId(12L)).thenReturn(List.of(badTask));
        when(reviewRecoveryTaskService.getTasksByOrderId(12L)).thenReturn(List.of());
        when(productService.findAll()).thenReturn(List.of(Product.builder()
                .id(4L)
                .title("Product")
                .photo(true)
                .build()));

        OrderDetailsResponse response = assembler.buildOrderDetailsResponse(12L, admin);

        assertEquals("Детали заказа Review Product", response.title());
        assertEquals("Review Company", response.companyTitle());
        assertEquals(BigDecimal.valueOf(125), response.totalSumWithBadReviews());
        assertEquals(1, response.badReviewSummary().done());
        assertEquals("https://review", response.reviews().getFirst().urlPhoto());
        assertEquals(1, response.badReviewTasks().size());
        assertEquals("snapshot-login", response.badReviewTasks().getFirst().botLogin());
        assertEquals("snapshot-password", response.badReviewTasks().getFirst().botPassword());
        assertEquals("самостоятельный плохой текст", response.badReviewTasks().getFirst().taskText());
        assertEquals(0, response.recoveryTasks().size());
        assertTrue(response.canEditReviewPublish());
    }

    @Test
    void buildOrderDetailsResponseLetsWorkerDeleteReviewsAndOnlyEditReviewVigul() {
        Authentication worker = authentication("ROLE_WORKER");
        ReviewDTOOne review = ReviewDTOOne.builder()
                .id(51L)
                .orderId(12L)
                .companyTitle("Review Company")
                .productTitle("Review Product")
                .build();
        OrderDTO order = OrderDTO.builder()
                .id(12L)
                .sum(BigDecimal.ZERO)
                .status(OrderStatusDTO.builder().title("Публикация").build())
                .build();

        when(orderService.getOrderDTO(12L)).thenReturn(order);
        when(reviewService.getReviewsAllByOrderId(12L)).thenReturn(List.of(review));
        when(badReviewTaskService.getSummaryForOrder(12L)).thenReturn(BadReviewTaskSummary.empty());
        when(badReviewTaskService.getTasksByOrderId(12L)).thenReturn(List.of());
        when(reviewRecoveryTaskService.getTasksByOrderId(12L)).thenReturn(List.of());
        when(productService.findAll()).thenReturn(List.of());

        OrderDetailsResponse response = assembler.buildOrderDetailsResponse(12L, worker);

        assertTrue(response.canEditReviewVigul());
        assertFalse(response.canEditReviewDates());
        assertFalse(response.canEditReviewPublish());
        assertTrue(response.canDeleteReviews());
    }

    @Test
    void buildOrderDetailsResponseIncludesRecoveryTasks() {
        Authentication admin = authentication("ROLE_ADMIN");
        ReviewDTOOne review = ReviewDTOOne.builder()
                .id(51L)
                .orderId(12L)
                .companyTitle("Review Company")
                .productTitle("Review Product")
                .build();
        OrderDTO order = OrderDTO.builder()
                .id(12L)
                .sum(BigDecimal.ZERO)
                .status(OrderStatusDTO.builder().title("Оплачен").build())
                .build();
        ReviewRecoveryBatch batch = ReviewRecoveryBatch.builder()
                .id(70L)
                .status(ReviewRecoveryBatchStatus.COMPLETED)
                .build();
        ReviewRecoveryTask task = ReviewRecoveryTask.builder()
                .id(80L)
                .batch(batch)
                .status(ReviewRecoveryTaskStatus.DONE)
                .sourceReview(new com.hunt.otziv.r_review.model.Review())
                .recoveryText("восстановленный текст")
                .scheduledDate(LocalDate.of(2026, 5, 14))
                .completedDate(LocalDate.of(2026, 5, 15))
                .botFioSnapshot("Старый аккаунт")
                .botLoginSnapshot("login")
                .botPasswordSnapshot("password")
                .build();
        task.getSourceReview().setId(51L);

        when(orderService.getOrderDTO(12L)).thenReturn(order);
        when(reviewService.getReviewsAllByOrderId(12L)).thenReturn(List.of(review));
        when(badReviewTaskService.getSummaryForOrder(12L)).thenReturn(BadReviewTaskSummary.empty());
        when(badReviewTaskService.getTasksByOrderId(12L)).thenReturn(List.of());
        when(reviewRecoveryTaskService.getTasksByOrderId(12L)).thenReturn(List.of(task));
        when(productService.findAll()).thenReturn(List.of());

        OrderDetailsResponse response = assembler.buildOrderDetailsResponse(12L, admin);

        assertEquals(1, response.recoveryTasks().size());
        assertEquals("Восстановлено", response.recoveryTasks().getFirst().status());
        assertEquals("восстановленный текст", response.recoveryTasks().getFirst().recoveryText());
        assertEquals("Старый аккаунт", response.recoveryTasks().getFirst().botFio());
        assertEquals("Все восстановления выполнены", response.recoveryTasks().getFirst().batch().status());
    }

    private Authentication authentication(String authority) {
        return new UsernamePasswordAuthenticationToken(
                "user",
                "password",
                List.of(new SimpleGrantedAuthority(authority))
        );
    }
}
