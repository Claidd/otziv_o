package com.hunt.otziv.p_products.deletion.service;

import com.hunt.otziv.p_products.deletion.policy.OrderDeletionPolicy;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.CompanyStatus;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import com.hunt.otziv.p_products.next_order.service.NextOrderRequestService;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
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

    @Mock
    private BusinessAuditService businessAuditService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void adminDeletesReviewsDetailsAndOrderInOrder() {
        OrderDeletionService service = service();
        Order order = order(10L, "Архив");
        OrderDetails firstDetail = detail(List.of(review(1L), review(null), review(2L)));
        OrderDetails secondDetail = detail(null);
        Principal principal = () -> "admin";

        authenticateWithRole("ROLE_ADMIN");
        when(orderRepository.findByIdForCounterUpdate(10L)).thenReturn(Optional.of(order));
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
        inOrder.verify(orderRepository).findByIdForCounterUpdate(10L);
        inOrder.verify(commonInvoiceOrderRepository).findMembershipByOrderIdForRead(10L);
        inOrder.verify(orderDetailsService).findByOrderId(10L);
        inOrder.verify(paymentLinkArchiveService).archiveForDeletedOrder(10L);
        inOrder.verify(badReviewTaskService).deleteAllByOrderId(10L);
        inOrder.verify(reviewRecoveryTaskRepository).deleteByOrderId(10L);
        inOrder.verify(reviewRecoveryBatchRepository).deleteByOrderId(10L);
        inOrder.verify(nextOrderRequestRepository).deleteBySourceOrderId(10L);
        inOrder.verify(reviewService).deleteAllByIdIn(List.of(1L, 2L));
        inOrder.verify(orderDetailsService).deleteAllByOrderId(10L);
        inOrder.verify(entityManager).flush();
        inOrder.verify(entityManager).clear();
        inOrder.verify(orderRepository).deleteById(10L);
        verify(businessAuditService).recordSafely(
                eq("order_deleted"),
                eq("order"),
                eq(10L),
                eq(10L),
                isNull(),
                eq("Архив"),
                eq("deleted"),
                anyString()
        );
        verify(commonInvoiceOrderRepository, never()).deleteByOrderId(10L);
    }

    @Test
    void rolledBackTransactionDoesNotWriteOrderDeletedAudit() {
        OrderDeletionService service = service();
        Order order = order(19L, "Архив");

        authenticateWithRole("ROLE_ADMIN");
        when(orderRepository.findByIdForCounterUpdate(19L)).thenReturn(Optional.of(order));
        when(orderDetailsService.findByOrderId(19L)).thenReturn(List.of());
        beginSynchronizedTransaction();

        assertTrue(service.deleteOrder(19L, () -> "admin"));

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        verifyNoInteractions(businessAuditService);

        synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(businessAuditService);
    }

    @Test
    void committedTransactionWritesOrderDeletedAuditExactlyOnceAndContainsCallbackFailure() {
        OrderDeletionService service = service();
        Order order = order(20L, "Архив");

        authenticateWithRole("ROLE_ADMIN");
        when(orderRepository.findByIdForCounterUpdate(20L)).thenReturn(Optional.of(order));
        when(orderDetailsService.findByOrderId(20L)).thenReturn(List.of());
        doThrow(new IllegalStateException("audit unavailable"))
                .when(businessAuditService)
                .recordSafely(
                        eq("order_deleted"),
                        eq("order"),
                        eq(20L),
                        eq(20L),
                        isNull(),
                        eq("Архив"),
                        eq("deleted"),
                        anyString()
                );
        beginSynchronizedTransaction();

        assertTrue(service.deleteOrder(20L, () -> "admin"));

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        verifyNoInteractions(businessAuditService);

        assertDoesNotThrow(synchronizations.get(0)::afterCommit);
        synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(businessAuditService).recordSafely(
                eq("order_deleted"),
                eq("order"),
                eq(20L),
                eq(20L),
                isNull(),
                eq("Архив"),
                eq("deleted"),
                anyString()
        );
    }

    @Test
    void standaloneInvocationWithoutSynchronizationWritesAuditImmediately() {
        OrderDeletionService service = service();
        Order order = order(21L, "Архив");

        authenticateWithRole("ROLE_ADMIN");
        when(orderRepository.findByIdForCounterUpdate(21L)).thenReturn(Optional.of(order));
        when(orderDetailsService.findByOrderId(21L)).thenReturn(List.of());

        assertTrue(service.deleteOrder(21L, () -> "admin"));

        verify(businessAuditService).recordSafely(
                eq("order_deleted"),
                eq("order"),
                eq(21L),
                eq(21L),
                isNull(),
                eq("Архив"),
                eq("deleted"),
                anyString()
        );
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    }

    @Test
    void activeTransactionWithoutSynchronizationDoesNotWritePrematureAudit() {
        OrderDeletionService service = service();
        Order order = order(22L, "Архив");

        authenticateWithRole("ROLE_ADMIN");
        when(orderRepository.findByIdForCounterUpdate(22L)).thenReturn(Optional.of(order));
        when(orderDetailsService.findByOrderId(22L)).thenReturn(List.of());
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertTrue(service.deleteOrder(22L, () -> "admin"));

        verifyNoInteractions(businessAuditService);
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    }

    @Test
    void linkedOrderFailsClosedBeforeAnyDependentRowsAreReadOrDeleted() {
        OrderDeletionService service = service();
        Order order = order(18L, "Архив");

        authenticateWithRole("ROLE_ADMIN");
        when(orderRepository.findByIdForCounterUpdate(18L)).thenReturn(Optional.of(order));
        when(commonInvoiceOrderRepository.findMembershipByOrderIdForRead(18L))
                .thenReturn(Optional.of(new CommonInvoiceOrder()));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.deleteOrder(18L, () -> "admin")
        );

        assertSame(org.springframework.http.HttpStatus.CONFLICT, exception.getStatusCode());
        assertTrue(exception.getReason().contains("только вместе"));
        InOrder lockOrder = inOrder(orderRepository, commonInvoiceOrderRepository);
        lockOrder.verify(orderRepository).findByIdForCounterUpdate(18L);
        lockOrder.verify(commonInvoiceOrderRepository).findMembershipByOrderIdForRead(18L);
        verify(orderDetailsService, never()).findByOrderId(18L);
        verify(paymentLinkArchiveService, never()).archiveForDeletedOrder(18L);
        verify(badReviewTaskService, never()).deleteAllByOrderId(18L);
        verify(reviewRecoveryTaskRepository, never()).deleteByOrderId(18L);
        verify(reviewRecoveryBatchRepository, never()).deleteByOrderId(18L);
        verify(nextOrderRequestRepository, never()).deleteBySourceOrderId(18L);
        verify(commonInvoiceOrderRepository, never()).deleteByOrderId(18L);
        verify(orderDetailsService, never()).deleteAllByOrderId(18L);
        verify(orderRepository, never()).deleteById(18L);
    }

    @Test
    void paymentArchiveBlockerStopsBeforeAnyDependentRowsAreDeleted() {
        OrderDeletionService service = service();
        Order order = order(17L, "Архив");

        authenticateWithRole("ROLE_ADMIN");
        when(orderRepository.findByIdForCounterUpdate(17L)).thenReturn(Optional.of(order));
        when(orderDetailsService.findByOrderId(17L)).thenReturn(List.of());
        when(paymentLinkArchiveService.archiveForDeletedOrder(17L))
                .thenThrow(new IllegalStateException("payment side effect pending"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.deleteOrder(17L, () -> "admin")
        );

        assertTrue(exception.getMessage().contains("payment side effect pending"));
        verify(badReviewTaskService, never()).deleteAllByOrderId(17L);
        verify(reviewRecoveryTaskRepository, never()).deleteByOrderId(17L);
        verify(reviewRecoveryBatchRepository, never()).deleteByOrderId(17L);
        verify(nextOrderRequestRepository, never()).deleteBySourceOrderId(17L);
        verify(commonInvoiceOrderRepository, never()).deleteByOrderId(17L);
        verify(orderDetailsService, never()).deleteAllByOrderId(17L);
        verify(orderRepository, never()).deleteById(17L);
    }

    @Test
    void adminCanDeleteWhenKeycloakDefaultRoleComesFirst() {
        OrderDeletionService service = service();
        Order order = order(13L, "В проверку");

        authenticateWithRoles("ROLE_default-roles-otziv", "ROLE_ADMIN");
        when(orderRepository.findByIdForCounterUpdate(13L)).thenReturn(Optional.of(order));
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
    void managerCanDeleteCorrectionOrder() {
        OrderDeletionService service = service();
        Order order = order(11L, "Коррекция");

        authenticateWithRole("ROLE_manager");
        when(orderRepository.findByIdForCounterUpdate(11L)).thenReturn(Optional.of(order));
        when(orderDetailsService.findByOrderId(11L)).thenReturn(List.of());

        boolean result = service.deleteOrder(11L, () -> "manager");

        assertTrue(result);
        verify(orderDetailsService).deleteAllByOrderId(11L);
        verifyNoInteractions(reviewService);
        verify(entityManager).flush();
        verify(entityManager).clear();
        verify(orderRepository).deleteById(11L);
    }

    @Test
    void managerCannotDeleteApprovedOrder() {
        OrderDeletionService service = service();
        Order order = order(14L, "Публикация");

        authenticateWithRole("ROLE_MANAGER");
        when(orderRepository.findByIdForCounterUpdate(14L)).thenReturn(Optional.of(order));

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
        when(orderRepository.findByIdForCounterUpdate(12L)).thenReturn(Optional.of(order));
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
        when(orderRepository.findByIdForCounterUpdate(15L)).thenReturn(Optional.of(order));
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
        when(orderRepository.findByIdForCounterUpdate(16L)).thenReturn(Optional.of(order));
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
                entityManager,
                businessAuditService
        );
    }

    private void beginSynchronizedTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
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
