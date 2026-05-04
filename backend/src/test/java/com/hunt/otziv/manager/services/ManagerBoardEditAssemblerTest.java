package com.hunt.otziv.manager.services;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
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
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.services.AmountService;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
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

    @Spy
    private ManagerPermissionService managerPermissionService = new ManagerPermissionService();

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

        when(orderService.getOrderDTO(12L)).thenReturn(order);
        when(reviewService.getReviewsAllByOrderId(12L)).thenReturn(List.of(review));
        when(badReviewTaskService.getSummaryForOrder(12L))
                .thenReturn(new BadReviewTaskSummary(1, 0, 1, 0, BigDecimal.valueOf(25), BigDecimal.ZERO));
        when(badReviewTaskService.getTasksByOrderId(12L)).thenReturn(List.of());
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
        assertTrue(response.canEditReviewPublish());
    }

    private Authentication authentication(String authority) {
        return new UsernamePasswordAuthenticationToken(
                "user",
                "password",
                List.of(new SimpleGrantedAuthority(authority))
        );
    }
}
