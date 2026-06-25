package com.hunt.otziv.p_products.deletion;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.next_order.NextOrderRequestRepository;
import com.hunt.otziv.p_products.next_order.NextOrderRequestService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.payments.service.PaymentLinkArchiveService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryBatchRepository;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryTaskRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderDeletionServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderDetailsService orderDetailsService;

    @Mock
    private ReviewService reviewService;

    @Mock
    private BadReviewTaskService badReviewTaskService;

    @Mock
    private NextOrderRequestService nextOrderRequestService;

    @Mock
    private NextOrderRequestRepository nextOrderRequestRepository;

    @Mock
    private ReviewRecoveryTaskRepository reviewRecoveryTaskRepository;

    @Mock
    private ReviewRecoveryBatchRepository reviewRecoveryBatchRepository;

    @Mock
    private CommonInvoiceOrderRepository commonInvoiceOrderRepository;

    @Mock
    private PaymentLinkArchiveService paymentLinkArchiveService;

    @Mock
    private CompanyService companyService;

    @Mock
    private CompanyStatusService companyStatusService;

    @Mock
    private EntityManager entityManager;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminDeletesReviewsDetailsAndOrderInOrder() {
        OrderDeletionService service = service();
        Order order = order(10L, "Архив");
        OrderDetails firstDetail = detail(List.of(review(1L), review(null), review(2L)));
        OrderDetails secondDetail = detail(null);
        Principal principal = () -> "admin";

        authenticateWithRole("ROLE_ADMIN");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderDetailsService.findByOrderId(10L)).thenReturn(List.of(firstDetail, secondDetail));
        when(badReviewTaskService.deleteAllByOrderId(10L)).thenReturn(3);

        boolean result = service.deleteOrder(10L, principal);

        assertTrue(result);
        InOrder inOrder = inOrder(
                badReviewTaskService,
                reviewRecoveryTaskRepository,
                reviewRecoveryBatchRepository,
                nextOrderRequestRepository,
                commonInvoiceOrderRepository,
                reviewService,
                orderDetailsService,
                paymentLinkArchiveService,
                entityManager,
                orderRepository
        );
        inOrder.verify(badReviewTaskService).deleteAllByOrderId(10L);
        inOrder.verify(reviewRecoveryTaskRepository).deleteByOrderId(10L);
        inOrder.verify(reviewRecoveryBatchRepository).deleteByOrderId(10L);
        inOrder.verify(nextOrderRequestRepository).deleteBySourceOrderId(10L);
        inOrder.verify(commonInvoiceOrderRepository).deleteByOrderId(10L);
        inOrder.verify(reviewService).deleteAllByIdIn(List.of(1L, 2L));
        inOrder.verify(orderDetailsService).deleteAllByOrderId(10L);
        inOrder.verify(paymentLinkArchiveService).archiveForDeletedOrder(10L);
        inOrder.verify(entityManager).flush();
        inOrder.verify(entityManager).clear();
        inOrder.verify(orderRepository).deleteById(10L);
    }

    @Test
    void adminCanDeleteWhenKeycloakDefaultRoleComesFirst() {
        OrderDeletionService service = service();
        Order order = order(13L, "В проверку");

        authenticateWithRoles("ROLE_default-roles-otziv", "ROLE_ADMIN");
        when(orderRepository.findById(13L)).thenReturn(Optional.of(order));
        when(orderDetailsService.findByOrderId(13L)).thenReturn(List.of());

        boolean result = service.deleteOrder(13L, () -> "admin");

        assertTrue(result);
        verify(orderDetailsService).deleteAllByOrderId(13L);
        verify(entityManager).flush();
        verify(entityManager).clear();
        verify(orderRepository).deleteById(13L);
        verifyNoInteractions(reviewService);
    }

    @Test
    void managerCannotDeleteCorrectionOrder() {
        OrderDeletionService service = service();
        Order order = order(11L, "Коррекция");

        authenticateWithRole("ROLE_manager");
        when(orderRepository.findById(11L)).thenReturn(Optional.of(order));

        boolean result = service.deleteOrder(11L, () -> "manager");

        assertFalse(result);
        verify(orderDetailsService, never()).findByOrderId(11L);
        verifyNoInteractions(reviewService);
        verifyNoInteractions(entityManager);
        verify(orderRepository, never()).deleteById(11L);
    }

    @Test
    void managerCannotDeleteApprovedOrder() {
        OrderDeletionService service = service();
        Order order = order(14L, "Публикация");

        authenticateWithRole("ROLE_MANAGER");
        when(orderRepository.findById(14L)).thenReturn(Optional.of(order));

        boolean result = service.deleteOrder(14L, () -> "manager");

        assertFalse(result);
        verify(orderDetailsService, never()).findByOrderId(14L);
        verifyNoInteractions(entityManager);
        verifyNoInteractions(reviewService);
        verify(orderRepository, never()).deleteById(14L);
    }

    @Test
    void managerCanDeleteNewOrderWhenRoleComesFromUserDetailsPrincipal() {
        OrderDeletionService service = service();
        Order order = order(12L, "Новый");
        User userDetails = new User(
                "manager",
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))
        );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, "password")
        );
        when(orderRepository.findById(12L)).thenReturn(Optional.of(order));
        when(orderDetailsService.findByOrderId(12L)).thenReturn(List.of());

        boolean result = service.deleteOrder(12L, () -> "manager");

        assertTrue(result);
        verify(orderDetailsService).deleteAllByOrderId(12L);
        verify(entityManager).flush();
        verify(entityManager).clear();
        verify(orderRepository).deleteById(12L);
        verifyNoInteractions(reviewService);
    }

    @Test
    void deletingLastNewOrderMovesCompanyToStop() {
        OrderDeletionService service = service();
        Company company = company(100L, "В работе");
        CompanyStatus stop = companyStatus("На стопе");
        Order order = order(15L, "Новый");
        order.setCompany(company);

        authenticateWithRole("ROLE_MANAGER");
        when(orderRepository.findById(15L)).thenReturn(Optional.of(order));
        when(orderDetailsService.findByOrderId(15L)).thenReturn(List.of());
        when(orderRepository.existsActiveOrderByCompanyId(eq(100L), eq(Set.of("Оплачено", "Архив"))))
                .thenReturn(false);
        when(companyService.getCompaniesById(100L)).thenReturn(company);
        when(companyStatusService.getStatusByTitle("На стопе")).thenReturn(stop);

        boolean result = service.deleteOrder(15L, () -> "manager");

        assertTrue(result);
        verify(orderRepository).deleteById(15L);
        verify(nextOrderRequestService).cancelForDeletedCreatedOrder(order);
        verify(companyService).save(company);
        assertSame(stop, company.getStatus());
    }

    @Test
    void deletingNewOrderKeepsCompanyStatusWhenAnotherActiveOrderExists() {
        OrderDeletionService service = service();
        Company company = company(101L, "В работе");
        Order order = order(16L, "Новый");
        order.setCompany(company);

        authenticateWithRole("ROLE_MANAGER");
        when(orderRepository.findById(16L)).thenReturn(Optional.of(order));
        when(orderDetailsService.findByOrderId(16L)).thenReturn(List.of());
        when(orderRepository.existsActiveOrderByCompanyId(eq(101L), eq(Set.of("Оплачено", "Архив"))))
                .thenReturn(true);

        boolean result = service.deleteOrder(16L, () -> "manager");

        assertTrue(result);
        verify(companyService, never()).save(company);
        verify(companyStatusService, never()).getStatusByTitle("На стопе");
    }

    private OrderDeletionService service() {
        return new OrderDeletionService(
                orderRepository,
                orderDetailsService,
                reviewService,
                badReviewTaskService,
                new OrderDeletionPolicy(),
                nextOrderRequestService,
                nextOrderRequestRepository,
                reviewRecoveryTaskRepository,
                reviewRecoveryBatchRepository,
                commonInvoiceOrderRepository,
                paymentLinkArchiveService,
                companyService,
                companyStatusService,
                entityManager
        );
    }

    private void authenticateWithRole(String role) {
        authenticateWithRoles(role);
    }

    private void authenticateWithRoles(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "user",
                        "password",
                        List.of(roles).stream()
                                .map(SimpleGrantedAuthority::new)
                                .toList()
                )
        );
    }

    private Order order(Long id, String statusTitle) {
        OrderStatus status = new OrderStatus();
        status.setTitle(statusTitle);

        Order order = new Order();
        order.setId(id);
        order.setStatus(status);
        return order;
    }

    private Company company(Long id, String statusTitle) {
        Company company = new Company();
        company.setId(id);
        company.setStatus(companyStatus(statusTitle));
        return company;
    }

    private CompanyStatus companyStatus(String title) {
        CompanyStatus status = new CompanyStatus();
        status.setTitle(title);
        return status;
    }

    private OrderDetails detail(List<Review> reviews) {
        OrderDetails detail = new OrderDetails();
        detail.setId(UUID.randomUUID());
        detail.setReviews(reviews);
        return detail;
    }

    private Review review(Long id) {
        Review review = new Review();
        review.setId(id);
        return review;
    }
}
