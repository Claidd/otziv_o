package com.hunt.otziv.p_products.service;

import com.hunt.otziv.b_bots.service.BotService;
import com.hunt.otziv.c_categories.service.CategoryService;
import com.hunt.otziv.c_categories.service.SubCategoryService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.c_companies.service.CompanyStatusService;
import com.hunt.otziv.c_companies.service.FilialService;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.next_order.service.NextOrderRequestService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.service.ManagerService;
import com.hunt.otziv.u_users.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCreationServiceRepeatTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderDetailsService orderDetailsService;
    @Mock private CompanyService companyService;
    @Mock private CompanyStatusService companyStatusService;
    @Mock private ProductService productService;
    @Mock private ReviewService reviewService;
    @Mock private BotService botService;
    @Mock private ManagerService managerService;
    @Mock private WorkerService workerService;
    @Mock private OrderStatusService orderStatusService;
    @Mock private SubCategoryService subCategoryService;
    @Mock private CategoryService categoryService;
    @Mock private FilialService filialService;
    @Mock private ReviewRepository reviewRepository;
    @Mock private BotAssignmentService botAssignmentService;
    @Mock private NextOrderRequestService nextOrderRequestService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CommonBillingService commonBillingService;

    @Test
    void repeatUsesActualPreviousReviewProductAndSnapshotPrice() {
        Product staleDetailProduct = product(1L, "200.00");
        Product actualReviewProduct = product(2L, "999.00");
        Order source = sourceOrder(staleDetailProduct, actualReviewProduct, "250.00");
        Company company = source.getCompany();
        Worker worker = source.getWorker();
        Manager manager = source.getManager();
        Filial filial = source.getFilial();
        OrderStatus newStatus = new OrderStatus();
        newStatus.setTitle("Новый");

        when(workerService.getWorkerById(worker.getId())).thenReturn(worker);
        when(companyService.getCompaniesById(company.getId())).thenReturn(company);
        when(managerService.getManagerById(manager.getId())).thenReturn(manager);
        when(filialService.getFilial(filial.getId())).thenReturn(filial);
        when(orderStatusService.getOrderStatusByTitle("Новый")).thenReturn(newStatus);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(500L);
            }
            return order;
        });
        when(orderDetailsService.save(any(OrderDetails.class))).thenAnswer(invocation -> invocation.getArgument(0));
        List<Review> createdReviews = new ArrayList<>();
        when(botAssignmentService.assignBotsToNewReviews(any(OrderDTO.class), any(OrderDetails.class)))
                .thenAnswer(invocation -> {
                    OrderDetails detail = invocation.getArgument(1);
                    Review draft = new Review();
                    draft.setOrderDetails(detail);
                    draft.setProduct(detail.getProduct());
                    draft.setPrice(detail.getProduct().getPrice());
                    createdReviews.add(draft);
                    return createdReviews;
                });

        OrderCreationServiceImpl service = service();
        OrderDTO repeatOrder = service.convertToOrderDTOToRepeat(source);

        service.createRepeatedOrderWithReviews(source, repeatOrder);

        Review createdReview = createdReviews.getFirst();
        OrderDetails createdDetail = createdReview.getOrderDetails();
        assertSame(actualReviewProduct, createdReview.getProduct());
        assertEquals(new BigDecimal("250.00"), createdReview.getPrice());
        assertSame(actualReviewProduct, createdDetail.getProduct());
        assertEquals(new BigDecimal("250.00"), createdDetail.getPrice());
        assertEquals(1, createdDetail.getAmount());
        assertEquals(new BigDecimal("250.00"), createdDetail.getOrder().getSum());
        assertEquals(1, createdDetail.getOrder().getAmount());
        verifyNoInteractions(productService);
    }

    private OrderCreationServiceImpl service() {
        return new OrderCreationServiceImpl(
                orderRepository,
                orderDetailsService,
                companyService,
                companyStatusService,
                productService,
                reviewService,
                botService,
                managerService,
                workerService,
                orderStatusService,
                subCategoryService,
                categoryService,
                filialService,
                reviewRepository,
                botAssignmentService,
                nextOrderRequestService,
                eventPublisher,
                commonBillingService
        );
    }

    private Order sourceOrder(Product detailProduct, Product reviewProduct, String reviewPrice) {
        User user = new User();
        Worker worker = new Worker();
        worker.setId(7L);
        worker.setUser(user);
        Manager manager = new Manager();
        manager.setId(8L);
        manager.setUser(user);
        manager.setPayText("pay");
        manager.setClientId("client");
        Filial filial = new Filial();
        filial.setId(9L);
        filial.setTitle("Филиал");
        Company company = new Company();
        company.setId(10L);
        company.setTitle("Компания");
        company.setManager(manager);
        company.setWorkers(Set.of(worker));
        company.setFilial(Set.of(filial));

        Order order = new Order();
        order.setId(11L);
        order.setAmount(1);
        order.setWorker(worker);
        order.setManager(manager);
        order.setCompany(company);
        order.setFilial(filial);
        OrderDetails detail = new OrderDetails();
        detail.setOrder(order);
        detail.setProduct(detailProduct);
        Review review = new Review();
        review.setId(12L);
        review.setOrderDetails(detail);
        review.setFilial(filial);
        review.setProduct(reviewProduct);
        review.setPrice(new BigDecimal(reviewPrice));
        detail.setReviews(List.of(review));
        order.setDetails(List.of(detail));
        return order;
    }

    private Product product(Long id, String price) {
        Product product = new Product();
        product.setId(id);
        product.setPrice(new BigDecimal(price));
        return product;
    }
}
