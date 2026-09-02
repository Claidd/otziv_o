package com.hunt.otziv.payments.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.dto.PaymentReturnManualResolutionOutcome;
import com.hunt.otziv.payments.dto.PaymentReturnManualResolutionRequest;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.PaymentLinkReturnOutboxRepository;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.repository.PaymentCheckRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentReturnManualReconciliationServiceTest {

    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentIssueReminderService paymentIssueReminderService;
    @Mock
    private BusinessAuditService businessAuditService;
    @Mock
    private PaymentCheckRepository paymentCheckRepository;
    @Mock
    private PaymentLinkReturnOutboxRepository returnOutboxRepository;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerResolvesManualMarkerWithLocksAuditAndReminderClose() {
        authenticate("owner@test", "ROLE_OWNER");
        Order order = manuallyReopenedOrder(42L);
        PaymentLink link = manualLink(7L, order);
        link.setLastError("payment_return_manual_reconciliation: исходная причина");
        stubLocked(link);
        stubInactiveExactCheck(link);

        var response = service().resolve(7L, new PaymentReturnManualResolutionRequest(
                PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY,
                "Откат сверен по банковской выписке",
                PaymentReturnManualReconciliationService.confirmationText(
                        PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY, 7L)
        ));

        assertEquals(PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY, response.outcome());
        assertEquals("APPLIED_MANUALLY", link.getReturnRecoveryOutcome());
        assertEquals("owner@test", link.getReturnRecoveryResolvedBy());
        assertEquals("Откат сверен по банковской выписке", link.getReturnRecoveryResolutionReason());
        org.junit.jupiter.api.Assertions.assertTrue(
                link.getLastError().startsWith("payment_return_manual_resolution_resolved:"));
        var locks = inOrder(paymentLinkRepository, orderRepository);
        locks.verify(paymentLinkRepository).findOrderIdById(7L);
        locks.verify(orderRepository).findByIdForCounterUpdate(42L);
        locks.verify(paymentLinkRepository).findByIdForUpdate(7L);
        verify(paymentLinkRepository).saveAndFlush(link);
        verify(paymentIssueReminderService, never()).resolveOrderIssueInCurrentTransaction(
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION, 7L);
        verify(businessAuditService).recordRequiredInCurrentTransaction(
                eq("PAYMENT_RETURN_MANUAL_RECONCILIATION_RESOLVED"),
                eq("PAYMENT_LINK"),
                eq(7L),
                eq(42L),
                eq(null),
                eq("payment_return_manual_reconciliation: исходная причина"),
                eq("APPLIED_MANUALLY"),
                org.mockito.ArgumentMatchers.contains("originalManualCause="
                        + "payment_return_manual_reconciliation: исходная причина")
        );
        verify(returnOutboxRepository).requeue(7L, 3L, "REFUNDED");
    }

    @Test
    void appliedManuallyIsRejectedWhileOrderIsPaidAndExactCheckIsActive() {
        authenticate("owner@test", "ROLE_OWNER");
        Order order = order(42L);
        order.setStatus(OrderStatus.builder().id(1L).title("Оплачено").build());
        order.setCompany(Company.builder().id(9L).build());
        PaymentLink link = manualLink(7L, order);
        stubLocked(link);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service().resolve(7L, new PaymentReturnManualResolutionRequest(
                        PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY,
                        "Финансовый откат еще не выполнен",
                        PaymentReturnManualReconciliationService.confirmationText(
                                PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY, 7L)
                )));

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, failure.getStatusCode());
        verify(paymentLinkRepository, never()).saveAndFlush(link);
        verify(returnOutboxRepository, never()).requeue(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                anyString());
    }

    @Test
    void appliedManuallyRejectsReminderThatStillCarriesSettledOrderFlags() {
        authenticate("owner@test", "ROLE_OWNER");
        Order order = manuallyReopenedOrder(42L);
        order.setComplete(true);
        order.setPayDay(LocalDate.of(2026, 9, 1));
        PaymentLink link = manualLink(7L, order);
        stubLocked(link);

        assertThrows(ResponseStatusException.class, () -> service().resolve(7L,
                new PaymentReturnManualResolutionRequest(
                        PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY,
                        "Статус изменен, но финансовые флаги не очищены",
                        PaymentReturnManualReconciliationService.confirmationText(
                                PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY, 7L)
                )));

        verify(paymentCheckRepository, never()).findByIdForUpdate(81L);
        verify(returnOutboxRepository, never()).requeue(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                anyString());
    }

    @Test
    void acceptedNoopRejectsAlreadyUnpaidReminderCycle() {
        authenticate("owner@test", "ROLE_OWNER");
        Order order = manuallyReopenedOrder(42L);
        PaymentLink link = manualLink(7L, order);
        stubLocked(link);

        assertThrows(ResponseStatusException.class, () -> service().resolve(7L,
                new PaymentReturnManualResolutionRequest(
                        PaymentReturnManualResolutionOutcome.ACCEPTED_NOOP,
                        "Откат якобы не нужен",
                        PaymentReturnManualReconciliationService.confirmationText(
                                PaymentReturnManualResolutionOutcome.ACCEPTED_NOOP, 7L)
                )));

        verify(paymentIssueReminderService, never()).resolveOrderIssueInCurrentTransaction(
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION, 7L);
        verify(paymentLinkRepository, never()).saveAndFlush(link);
    }

    @Test
    void appliedManuallyAllowsRepairedUnpaidCycleWhenOriginalCheckWasUnknown() {
        authenticate("owner@test", "ROLE_OWNER");
        Order order = manuallyReopenedOrder(42L);
        PaymentLink link = manualLink(7L, order);
        link.setReturnRecoveryPaymentCheckId(null);
        stubLocked(link);
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(42L)).thenReturn(List.of());

        var response = service().resolve(7L, new PaymentReturnManualResolutionRequest(
                PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY,
                "Ручной откат выполнен, исходный чек отсутствовал",
                PaymentReturnManualReconciliationService.confirmationText(
                        PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY, 7L)
        ));

        assertEquals(PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY, response.outcome());
        verify(paymentCheckRepository, never()).findByIdForUpdate(
                org.mockito.ArgumentMatchers.anyLong());
        verify(returnOutboxRepository).requeue(7L, 3L, "REFUNDED");
        verify(paymentIssueReminderService, never()).resolveOrderIssueInCurrentTransaction(
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION, 7L);
    }

    @Test
    void exactRetryIsIdempotentAndStillRemovesRecreatedReminder() {
        authenticate("admin@test", "ROLE_ADMIN");
        Order order = order(42L);
        PaymentLink link = manualLink(7L, order);
        link.setReturnRecoveryOutcome("ACCEPTED_NOOP");
        link.setReturnRecoveryResolvedAt(LocalDateTime.now());
        link.setReturnRecoveryResolvedBy("owner@test");
        link.setReturnRecoveryResolutionReason("Возврат не относится к текущему учету");
        stubLocked(link);

        var response = service().resolve(7L, new PaymentReturnManualResolutionRequest(
                PaymentReturnManualResolutionOutcome.ACCEPTED_NOOP,
                "Возврат не относится к текущему учету",
                PaymentReturnManualReconciliationService.confirmationText(
                        PaymentReturnManualResolutionOutcome.ACCEPTED_NOOP, 7L)
        ));

        assertEquals(PaymentReturnManualResolutionOutcome.ACCEPTED_NOOP, response.outcome());
        verify(paymentLinkRepository, never()).saveAndFlush(link);
        verify(businessAuditService, never()).recordRequiredInCurrentTransaction(
                anyString(), anyString(), eq(7L), eq(42L), eq(null), anyString(), anyString(), anyString());
        verify(paymentIssueReminderService).resolveOrderIssueInCurrentTransaction(
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION, 7L);
    }

    @Test
    void differentTerminalResolutionIsRejected() {
        authenticate("owner@test", "ROLE_OWNER");
        Order order = order(42L);
        PaymentLink link = manualLink(7L, order);
        link.setReturnRecoveryOutcome("ACCEPTED_NOOP");
        link.setReturnRecoveryResolvedAt(LocalDateTime.now());
        link.setReturnRecoveryResolvedBy("owner@test");
        link.setReturnRecoveryResolutionReason("Принято без отката");
        stubLocked(link);

        assertThrows(ResponseStatusException.class, () -> service().resolve(7L,
                new PaymentReturnManualResolutionRequest(
                        PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY,
                        "Принято без отката",
                        PaymentReturnManualReconciliationService.confirmationText(
                                PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY, 7L)
                )));

        verify(paymentLinkRepository, never()).saveAndFlush(link);
    }

    @Test
    void confirmationForAnotherLinkCannotResolveCurrentManualMarker() {
        authenticate("owner@test", "ROLE_OWNER");
        Order order = order(42L);
        PaymentLink link = manualLink(7L, order);
        stubLocked(link);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service().resolve(7L, new PaymentReturnManualResolutionRequest(
                        PaymentReturnManualResolutionOutcome.ACCEPTED_NOOP,
                        "Сверено в соседней вкладке",
                        PaymentReturnManualReconciliationService.confirmationText(
                                PaymentReturnManualResolutionOutcome.ACCEPTED_NOOP, 8L)
                )));

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, failure.getStatusCode());
        verify(paymentLinkRepository, never()).saveAndFlush(link);
        verify(paymentIssueReminderService, never()).resolveOrderIssueInCurrentTransaction(
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION, 7L);
    }

    @Test
    void managerCannotResolveEvenWhenServiceIsCalledDirectly() {
        authenticate("manager@test", "ROLE_MANAGER");

        assertThrows(ResponseStatusException.class, () -> service().resolve(7L,
                new PaymentReturnManualResolutionRequest(
                        PaymentReturnManualResolutionOutcome.ACCEPTED_NOOP,
                        "Причина",
                        PaymentReturnManualReconciliationService.confirmationText(
                                PaymentReturnManualResolutionOutcome.ACCEPTED_NOOP, 7L)
                )));

        verify(paymentLinkRepository, never()).findOrderIdById(7L);
    }

    private PaymentReturnManualReconciliationService service() {
        return new PaymentReturnManualReconciliationService(
                paymentLinkRepository,
                orderRepository,
                paymentIssueReminderService,
                businessAuditService,
                paymentCheckRepository,
                returnOutboxRepository
        );
    }

    private void stubLocked(PaymentLink link) {
        when(paymentLinkRepository.findOrderIdById(link.getId())).thenReturn(Optional.of(42L));
        when(orderRepository.findByIdForCounterUpdate(42L)).thenReturn(Optional.of(link.getOrder()));
        when(paymentLinkRepository.findByIdForUpdate(link.getId())).thenReturn(Optional.of(link));
    }

    private PaymentLink manualLink(Long id, Order order) {
        PaymentLink link = new PaymentLink();
        link.setId(id);
        link.setOrder(order);
        link.setReturnRecoveryProcessedAt(LocalDateTime.now());
        link.setReturnRecoveryPaymentCheckId(81L);
        link.setReturnRecoveryOutcome("MANUAL_RECONCILIATION");
        link.setStatus(com.hunt.otziv.payments.model.PaymentLinkStatus.REFUNDED);
        link.setRowVersion(3L);
        return link;
    }

    private Order order(Long id) {
        Order order = new Order();
        order.setId(id);
        return order;
    }

    private Order manuallyReopenedOrder(Long id) {
        Order order = order(id);
        order.setStatus(OrderStatus.builder().id(2L).title("Напоминание").build());
        order.setCompany(Company.builder().id(9L).build());
        return order;
    }

    private void stubInactiveExactCheck(PaymentLink link) {
        PaymentCheck check = PaymentCheck.builder()
                .id(link.getReturnRecoveryPaymentCheckId())
                .orderId(link.getOrder().getId())
                .companyId(link.getOrder().getCompany().getId())
                .paymentLinkId(link.getId())
                .paidAmount(5)
                .sum(new BigDecimal("100.00"))
                .active(false)
                .build();
        when(paymentCheckRepository.findByIdForUpdate(check.getId())).thenReturn(Optional.of(check));
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(link.getOrder().getId()))
                .thenReturn(List.of());
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username,
                        "n/a",
                        List.of(new SimpleGrantedAuthority(role))
                )
        );
    }
}
