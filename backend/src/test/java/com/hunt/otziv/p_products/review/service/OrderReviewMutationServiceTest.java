package com.hunt.otziv.p_products.review.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.contractor_payments.service.ContractorRouteAssignmentGuard;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.service.OrderDetailsService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.p_products.service.BotAssignmentService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.bot.service.ReviewAccountWalkScheduleService;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.u_users.model.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderReviewMutationServiceTest {

    @Mock
    private OrderDetailsService orderDetailsService;

    @Mock
    private BotAssignmentService botAssignmentService;

    @Mock
    private ReviewService reviewService;

    @Mock
    private CompanyService companyService;

    @Mock
    private ReviewAccountWalkScheduleService accountWalkScheduleService;

    @Mock
    private OrderAggregateMutationLockService orderAggregateMutationLockService;

    @Mock
    private WorkerAssignmentMutationGuardService assignmentMutationGuardService;

    @Mock
    private ContractorRouteAssignmentGuard contractorRouteAssignmentGuard;

    @Test
    void addNewReviewCreatesReviewRecalculatesTotalsAndIncrementsCompanyCounter() {
        OrderReviewMutationService service = service();
        Company company = company(2);
        Worker worker = worker(9L);
        Filial filial = filial();
        Product product = product("100.00");
        Review existing = review(1L, "50.00");
        Order order = order(10L, company, worker, filial);
        OrderDetails detail = detail(order, product, new ArrayList<>(List.of(existing)));
        order.setDetails(List.of(detail));
        Bot bot = bot(100L);

        when(orderAggregateMutationLockService.lock(10L)).thenReturn(order);
        when(botAssignmentService.assignBotForReviewChange(any(), any(), any())).thenReturn(bot);
        when(reviewService.save(any(Review.class))).thenAnswer(invocation -> {
            Review review = invocation.getArgument(0);
            review.setId(2L);
            return review;
        });

        assertTrue(service.addNewReview(10L));

        ArgumentCaptor<Review> reviewCaptor = ArgumentCaptor.forClass(Review.class);
        verify(reviewService).save(reviewCaptor.capture());
        Review created = reviewCaptor.getValue();

        assertEquals("Текст отзыва", created.getText());
        assertEquals("", created.getAnswer());
        assertFalse(created.isPublish());
        assertSame(company.getCategoryCompany(), created.getCategory());
        assertSame(company.getSubCategory(), created.getSubCategory());
        assertSame(detail, created.getOrderDetails());
        assertSame(bot, created.getBot());
        assertSame(filial, created.getFilial());
        assertSame(worker, created.getWorker());
        assertSame(product, created.getProduct());
        assertEquals(new BigDecimal("100.00"), created.getPrice());

        assertEquals(2, detail.getAmount());
        assertEquals(new BigDecimal("150.00"), detail.getPrice());
        assertEquals(2, order.getAmount());
        assertEquals(new BigDecimal("150.00"), order.getSum());
        assertEquals(3, company.getCounterNoPay());
        verify(orderDetailsService).save(detail);
        verify(orderDetailsService).saveOrder(order);
        verify(companyService).save(company);
    }

    @Test
    void addNewReviewReturnsFalseWhenOrderHasNoDetails() {
        OrderReviewMutationService service = service();
        Order order = order(11L, company(0), worker(1L), filial());

        when(orderAggregateMutationLockService.lock(11L)).thenReturn(order);

        assertFalse(service.addNewReview(11L));

        verifyNoInteractions(orderDetailsService, botAssignmentService, reviewService, companyService);
    }

    @Test
    void deleteNewReviewRemovesReviewRecalculatesTotalsAndDecrementsCompanyCounter() {
        OrderReviewMutationService service = service();
        Company company = company(1);
        Order order = order(12L, company, worker(2L), filial());
        Review kept = review(1L, "80.00");
        Review removed = review(2L, "40.00");
        OrderDetails detail = detail(order, product("80.00"), new ArrayList<>(List.of(kept, removed)));
        order.setDetails(List.of(detail));

        when(orderAggregateMutationLockService.lock(12L)).thenReturn(order);
        when(reviewService.deleteReviewFromLockedOrder(12L, 2L)).thenReturn(true);

        assertTrue(service.deleteNewReview(12L, 2L));

        assertEquals(List.of(kept), detail.getReviews());
        assertEquals(1, detail.getAmount());
        assertEquals(new BigDecimal("80.00"), detail.getPrice());
        assertEquals(1, order.getAmount());
        assertEquals(new BigDecimal("80.00"), order.getSum());
        assertEquals(0, company.getCounterNoPay());
        verify(reviewService).deleteReviewFromLockedOrder(12L, 2L);
        verify(orderDetailsService).save(detail);
        verify(orderDetailsService).saveOrder(order);
        verify(companyService).save(company);
    }

    @Test
    void deleteNewReviewReturnsFalseWhenReviewIsMissing() {
        OrderReviewMutationService service = service();
        Company company = company(1);
        Order order = order(13L, company, worker(3L), filial());
        OrderDetails detail = detail(order, product("20.00"), new ArrayList<>());
        order.setDetails(List.of(detail));

        when(orderAggregateMutationLockService.lock(13L)).thenReturn(order);
        assertFalse(service.deleteNewReview(13L, 99L));

        verify(reviewService, never()).deleteReviewFromLockedOrder(13L, 99L);
        verifyNoInteractions(orderDetailsService, botAssignmentService, companyService);
    }

    @Test
    void deleteNewReviewRejectsReviewFromAnotherOrderWithoutMutatingEitherAggregate() {
        OrderReviewMutationService service = service();
        Company companyA = company(2);
        Company companyB = company(4);
        Order orderA = order(21L, companyA, worker(21L), filial());
        Order orderB = order(22L, companyB, worker(22L), filial());
        Review reviewA = review(101L, "50.00");
        Review reviewB = review(202L, "70.00");
        OrderDetails detailA = detail(orderA, product("50.00"), new ArrayList<>(List.of(reviewA)));
        OrderDetails detailB = detail(orderB, product("70.00"), new ArrayList<>(List.of(reviewB)));
        reviewA.setOrderDetails(detailA);
        reviewB.setOrderDetails(detailB);
        orderA.setDetails(List.of(detailA));
        orderB.setDetails(List.of(detailB));
        when(orderAggregateMutationLockService.lock(21L)).thenReturn(orderA);

        assertFalse(service.deleteNewReview(21L, 202L));

        assertEquals(List.of(reviewA), detailA.getReviews());
        assertEquals(List.of(reviewB), detailB.getReviews());
        assertEquals(2, companyA.getCounterNoPay());
        assertEquals(4, companyB.getCounterNoPay());
        verify(reviewService, never()).deleteReviewFromLockedOrder(any(), any());
        verifyNoInteractions(orderDetailsService, botAssignmentService, companyService);
    }

    private OrderReviewMutationService service() {
        return new OrderReviewMutationService(
                orderDetailsService,
                botAssignmentService,
                reviewService,
                companyService,
                accountWalkScheduleService,
                orderAggregateMutationLockService,
                assignmentMutationGuardService,
                contractorRouteAssignmentGuard
        );
    }

    private Company company(int counterNoPay) {
        Company company = new Company();
        company.setCounterNoPay(counterNoPay);
        company.setCategoryCompany(new Category());
        company.setSubCategory(new SubCategory());
        return company;
    }

    private Order order(Long id, Company company, Worker worker, Filial filial) {
        Order order = new Order();
        order.setId(id);
        order.setCompany(company);
        order.setWorker(worker);
        order.setFilial(filial);
        return order;
    }

    private OrderDetails detail(Order order, Product product, List<Review> reviews) {
        OrderDetails detail = new OrderDetails();
        detail.setOrder(order);
        detail.setProduct(product);
        detail.setReviews(reviews);
        return detail;
    }

    private Product product(String price) {
        Product product = new Product();
        product.setPrice(new BigDecimal(price));
        return product;
    }

    private Review review(Long id, String price) {
        Review review = new Review();
        review.setId(id);
        review.setPrice(new BigDecimal(price));
        return review;
    }

    private Worker worker(Long id) {
        Worker worker = new Worker();
        worker.setId(id);
        return worker;
    }

    private Filial filial() {
        Filial filial = new Filial();
        filial.setId(1L);
        return filial;
    }

    private Bot bot(Long id) {
        Bot bot = new Bot();
        bot.setId(id);
        return bot;
    }
}
