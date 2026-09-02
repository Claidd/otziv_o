package com.hunt.otziv.p_products.payment.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.contractor_payments.service.ContractorRewardLedgerService;
import com.hunt.otziv.contractor_payments.service.ContractorRouteAssignmentGuard;
import com.hunt.otziv.contractor_payments.service.ContractorCompletionRewardService;
import com.hunt.otziv.contractor_payments.service.ContractorRewardSourceCodes;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRuntimeSwitch;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRolloutStateService;
import com.hunt.otziv.p_products.deletion.service.OrderDeletionService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequest;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderStatusService;
import com.hunt.otziv.p_products.status.service.OrderCompanyStatusService;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.repository.PaymentCheckRepository;
import com.hunt.otziv.z_zp.model.Zp;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPaymentCancellationServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderStatusService orderStatusService;
    @Mock private OrderCompanyStatusService orderCompanyStatusService;
    @Mock private CompanyService companyService;
    @Mock private PaymentCheckRepository paymentCheckRepository;
    @Mock private NextOrderRequestRepository nextOrderRequestRepository;
    @Mock private OrderDeletionService orderDeletionService;
    @Mock private PaymentLinkRepository paymentLinkRepository;
    @Mock private PaymentLinkService paymentLinkService;
    @Mock private PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;
    @Mock private BusinessAuditService businessAuditService;
    @Mock private CommonBillingService commonBillingService;
    @Mock private ContractorRewardLedgerService contractorRewardLedgerService;
    @Mock private ContractorRouteAssignmentGuard contractorRouteAssignmentGuard;
    @Mock private ContractorCompletionRewardService contractorCompletionRewardService;
    @Mock private ContractorPaymentRolloutStateService rolloutStateService;
    @Mock private ContractorPaymentRuntimeSwitch contractorPaymentRuntimeSwitch;

    @InjectMocks
    private OrderPaymentCancellationService service;

    @Test
    void cancelPaymentDetachesAutoCreatedNextOrderBeforeStandaloneDeletion() {
        Order source = order(10L, "Оплачено");
        source.setAmount(3);
        Order created = order(20L, "Новый");
        NextOrderRequest request = new NextOrderRequest();
        request.setStatus(NextOrderRequestStatus.CREATED);
        request.setSourceOrder(source);
        request.setCreatedOrder(created);
        OrderStatus reminder = status("Напоминание");
        Principal principal = () -> "admin";

        stubFinancialRollback(source, new BigDecimal("1000.00"));
        when(nextOrderRequestRepository.findBySourceOrderId(10L)).thenReturn(Optional.of(request));
        when(commonBillingService.detachOrderForDeletion(20L)).thenReturn(true);
        when(orderDeletionService.deleteOrder(any(), any())).thenReturn(true);
        when(orderStatusService.getOrderStatusByTitle("Напоминание")).thenReturn(reminder);

        service.cancelPayment(10L, principal);

        var deletionOrder = inOrder(commonBillingService, orderDeletionService);
        deletionOrder.verify(commonBillingService).detachOrderForDeletion(20L);
        deletionOrder.verify(orderDeletionService).deleteOrder(20L, principal);
        assertEquals("Напоминание", source.getStatus().getTitle());
        verify(paymentLinkService).createForOrder(10L);
    }

    @Test
    void deactivatedRewardsAreSynchronizedBeforeReplacementPaymentLink() {
        Order source = order(11L, "Оплачено");
        source.setAmount(2);
        Zp reward = new Zp();
        reward.setId(71L);
        reward.setActive(true);
        OrderStatus reminder = status("Напоминание");
        Principal principal = () -> "admin";

        stubFinancialRollback(source, new BigDecimal("1000.00"));
        when(nextOrderRequestRepository.findBySourceOrderId(11L)).thenReturn(Optional.empty());
        when(contractorRewardLedgerService.lockActiveOrderRewardsAndRequireCancellationRepresentable(11L))
                .thenReturn(List.of(reward));
        when(orderStatusService.getOrderStatusByTitle("Напоминание")).thenReturn(reminder);

        service.cancelPayment(11L, principal);

        var financialOrder = inOrder(contractorRewardLedgerService, contractorCompletionRewardService, paymentLinkService);
        financialOrder.verify(contractorRewardLedgerService)
                .lockActiveOrderRewardsAndRequireCancellationRepresentable(11L);
        financialOrder.verify(contractorCompletionRewardService)
                .deactivateOrderPaymentAccruals(11L, "manual_payment_cancellation");
        financialOrder.verify(paymentLinkService).createForOrder(11L);
    }

    @Test
    void everyActiveOrderRewardIsDeactivatedWhenClientPaymentIsCanceled() {
        Order source = order(14L, "Оплачено");
        source.setAmount(2);
        Zp legacy = new Zp();
        legacy.setId(72L);
        legacy.setActive(true);
        legacy.setSource(ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST);
        stubFinancialRollback(source, new BigDecimal("1000.00"));
        when(nextOrderRequestRepository.findBySourceOrderId(14L)).thenReturn(Optional.empty());
        when(contractorRewardLedgerService.lockActiveOrderRewardsAndRequireCancellationRepresentable(14L))
                .thenReturn(List.of(legacy));
        when(orderStatusService.getOrderStatusByTitle("Напоминание")).thenReturn(status("Напоминание"));
        service.cancelPayment(14L, () -> "admin");

        verify(contractorRewardLedgerService).lockActiveOrderRewardsAndRequireCancellationRepresentable(14L);
        verify(contractorCompletionRewardService)
                .deactivateOrderPaymentAccruals(14L, "manual_payment_cancellation");
        verify(contractorCompletionRewardService, never()).migrateLegacyRewardsBeforePaymentCancellation(14L);
    }

    @Test
    void cancellationRollsBackOrderAndEveryDoneTaskInsteadOfLargestSalaryAmount() {
        Company company = Company.builder()
                .id(31L)
                .counterPay(20)
                .sumTotal(new BigDecimal("5000.00"))
                .build();
        Order source = order(16L, "Оплачено");
        source.setAmount(5);
        source.setCompany(company);
        PaymentCheck check = PaymentCheck.builder()
                .id(91L)
                .orderId(16L)
                .companyId(31L)
                .paymentStatusGuard(source.getStatus().getId())
                .sum(new BigDecimal("1400.00"))
                .paidAmount(7)
                .active(true)
                .build();
        Zp misleadingSalaryRow = new Zp();
        misleadingSalaryRow.setId(92L);
        misleadingSalaryRow.setAmount(6);
        misleadingSalaryRow.setActive(true);

        when(orderRepository.findByIdForCounterUpdate(16L)).thenReturn(Optional.of(source));
        when(paymentLinkRepository.existsByOrder_IdAndStatusIn(any(), anySet())).thenReturn(false);
        when(nextOrderRequestRepository.findBySourceOrderId(16L)).thenReturn(Optional.empty());
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(16L)).thenReturn(List.of(check));
        when(contractorRewardLedgerService.lockActiveOrderRewardsAndRequireCancellationRepresentable(16L))
                .thenReturn(List.of(misleadingSalaryRow));
        when(companyService.getCompaniesById(31L)).thenReturn(company);
        when(orderStatusService.getOrderStatusByTitle("Напоминание")).thenReturn(status("Напоминание"));

        service.cancelPayment(16L, () -> "admin");

        assertFalse(check.isActive());
        assertEquals(13, company.getCounterPay());
        assertEquals(0, new BigDecimal("3600.00").compareTo(company.getSumTotal()));
        verify(paymentCheckRepository).save(check);
        verify(orderRepository).flush();
        verify(companyService).save(company);
        verify(contractorCompletionRewardService)
                .deactivateOrderPaymentAccruals(16L, "manual_payment_cancellation");
    }

    @Test
    void legacyEarnedRewardIsCanceledAsBeforeWhileCompletionAttributionIsOff() {
        Order source = order(15L, "Оплачено");
        source.setAmount(2);
        Zp legacy = new Zp();
        legacy.setId(73L);
        legacy.setActive(true);
        legacy.setSource(ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST);
        stubFinancialRollback(source, new BigDecimal("1000.00"));
        when(nextOrderRequestRepository.findBySourceOrderId(15L)).thenReturn(Optional.empty());
        when(contractorRewardLedgerService.lockActiveOrderRewardsAndRequireCancellationRepresentable(15L))
                .thenReturn(List.of(legacy));
        when(orderStatusService.getOrderStatusByTitle("Напоминание")).thenReturn(status("Напоминание"));

        service.cancelPayment(15L, () -> "admin");

        verify(contractorRewardLedgerService).lockActiveOrderRewardsAndRequireCancellationRepresentable(15L);
        verify(contractorCompletionRewardService)
                .deactivateOrderPaymentAccruals(15L, "manual_payment_cancellation");
        verify(contractorCompletionRewardService, never()).migrateLegacyRewardsBeforePaymentCancellation(15L);
    }

    @Test
    void cancelPaymentStopsWhenClientAlreadyReceivedContractorRoute() {
        Order source = order(12L, "Оплачено");
        when(orderRepository.findByIdForCounterUpdate(12L)).thenReturn(Optional.of(source));
        doThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "реквизиты уже выданы"
        )).when(contractorRouteAssignmentGuard).requirePaymentCancellationAllowed(12L);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.cancelPayment(12L, () -> "admin")
        );

        assertEquals(409, error.getStatusCode().value());
        verify(contractorRouteAssignmentGuard).requirePaymentCancellationAllowed(12L);
        verify(paymentCheckRepository, never()).findByOrderIdAndActiveTrue(12L);
        verify(contractorRewardLedgerService, never())
                .lockActiveOrderRewardsAndRequireCancellationRepresentable(12L);
        verify(paymentLinkService, never()).createForOrder(12L);
    }

    @Test
    void cancelPaymentStopsWhenClientReportedCommonInvoicePayment() {
        Order source = order(13L, "Оплачено");
        when(orderRepository.findByIdForCounterUpdate(13L)).thenReturn(Optional.of(source));
        when(paymentLinkRepository.existsByOrder_IdAndStatusIn(any(), anySet())).thenReturn(false);
        when(commonBillingService.hasClientReportedPaymentForOrder(13L)).thenReturn(true);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.cancelPayment(13L, () -> "admin")
        );

        assertEquals(409, error.getStatusCode().value());
        verify(paymentCheckRepository, never()).findByOrderIdAndActiveTrue(13L);
        verify(contractorRewardLedgerService, never())
                .lockActiveOrderRewardsAndRequireCancellationRepresentable(13L);
    }

    @Test
    void cancellationWithoutExactlyOneActiveCheckFailsBeforeAnyFinancialSideEffect() {
        Order source = order(17L, "Оплачено");
        when(orderRepository.findByIdForCounterUpdate(17L)).thenReturn(Optional.of(source));
        when(paymentLinkRepository.existsByOrder_IdAndStatusIn(any(), anySet())).thenReturn(false);
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(17L)).thenReturn(List.of());

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.cancelPayment(17L, () -> "admin")
        );

        assertEquals(409, error.getStatusCode().value());
        verify(nextOrderRequestRepository, never()).findBySourceOrderId(17L);
        verify(contractorCompletionRewardService, never())
                .deactivateOrderPaymentAccruals(17L, "manual_payment_cancellation");
        verify(companyService, never()).save(any(Company.class));
        verify(paymentLinkService, never()).createForOrder(17L);
    }

    @Test
    void legacyCheckWithoutPaidAmountSnapshotFailsBeforeCompanyRollback() {
        Order source = order(19L, "Оплачено");
        source.setAmount(2);
        PaymentCheck legacyCheck = PaymentCheck.builder()
                .id(91L)
                .orderId(19L)
                .companyId(source.getCompany().getId())
                .paymentStatusGuard(source.getStatus().getId())
                .sum(new BigDecimal("1000.00"))
                .active(true)
                .build();
        when(orderRepository.findByIdForCounterUpdate(19L)).thenReturn(Optional.of(source));
        when(paymentLinkRepository.existsByOrder_IdAndStatusIn(any(), anySet())).thenReturn(false);
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(19L)).thenReturn(List.of(legacyCheck));
        when(contractorRewardLedgerService.lockActiveOrderRewardsAndRequireCancellationRepresentable(19L))
                .thenReturn(List.of());

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.cancelPayment(19L, () -> "admin")
        );

        assertEquals(409, error.getStatusCode().value());
        verify(nextOrderRequestRepository, never()).findBySourceOrderId(19L);
        verify(companyService, never()).getCompaniesById(source.getCompany().getId());
        verify(paymentCheckRepository, never()).save(legacyCheck);
        verify(contractorCompletionRewardService, never())
                .deactivateOrderPaymentAccruals(19L, "manual_payment_cancellation");
    }

    @Test
    void partialProviderReturnBlocksManualPaymentCancellation() {
        Order source = order(18L, "Оплачено");
        when(orderRepository.findByIdForCounterUpdate(18L)).thenReturn(Optional.of(source));
        when(paymentLinkRepository.existsByOrder_IdAndStatusIn(eq(18L), anySet())).thenReturn(true);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.cancelPayment(18L, () -> "admin")
        );

        assertEquals(409, error.getStatusCode().value());
        verify(paymentLinkRepository).existsByOrder_IdAndStatusIn(
                eq(18L),
                argThat(statuses -> statuses.contains(PaymentLinkStatus.PARTIAL_REVERSED)
                        && statuses.contains(PaymentLinkStatus.PARTIAL_REFUNDED))
        );
        verify(paymentCheckRepository, never()).findByOrderIdAndActiveTrue(18L);
        assertEquals("Оплачено", source.getStatus().getTitle());
    }

    @Test
    void restoredPaidOrderCancelsThroughItsRestoredExactReturnedSource() {
        Order source = order(21L, "Оплачено");
        source.setAmount(2);
        PaymentCheck check = stubFinancialRollback(source, new BigDecimal("1000.00"));
        check.setPaymentLinkId(77L);
        PaymentLink restoredSource = new PaymentLink();
        restoredSource.setId(77L);
        restoredSource.setOrder(source);
        restoredSource.setStatus(PaymentLinkStatus.REFUNDED);
        restoredSource.setConfirmedAmountKopecks(100_000L);
        restoredSource.setPaidAt(LocalDateTime.of(2026, 8, 1, 12, 0));
        when(paymentLinkRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(restoredSource));
        when(nextOrderRequestRepository.findBySourceOrderId(21L)).thenReturn(Optional.empty());
        when(orderStatusService.getOrderStatusByTitle("Напоминание")).thenReturn(status("Напоминание"));

        service.cancelPayment(21L, () -> "admin");

        assertEquals("APPLIED", restoredSource.getReturnRecoveryOutcome());
        assertEquals(check.getId(), restoredSource.getReturnRecoveryPaymentCheckId());
        verify(paymentLinkRepository).save(restoredSource);
        verify(businessAuditService).recordRequiredInCurrentTransaction(
                eq("order_payment_canceled"),
                eq("order"),
                eq(21L),
                eq(21L),
                eq(null),
                eq("Оплачено"),
                eq("Напоминание"),
                argThat(details -> details.contains("returnedSourceMarked=true"))
        );
    }

    @Test
    void requiredCancellationAuditFailureAbortsTheFinancialOperation() {
        Order source = order(22L, "Оплачено");
        source.setAmount(2);
        stubFinancialRollback(source, new BigDecimal("1000.00"));
        when(nextOrderRequestRepository.findBySourceOrderId(22L)).thenReturn(Optional.empty());
        when(orderStatusService.getOrderStatusByTitle("Напоминание")).thenReturn(status("Напоминание"));
        doThrow(new IllegalStateException("audit unavailable"))
                .when(businessAuditService).recordRequiredInCurrentTransaction(
                        any(), any(), any(), any(), any(), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> service.cancelPayment(22L, () -> "admin"));

        verify(businessAuditService).recordRequiredInCurrentTransaction(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    private Order order(Long id, String statusTitle) {
        Order order = new Order();
        order.setId(id);
        order.setStatus(status(statusTitle));
        order.setCompany(Company.builder()
                .id(1000L + id)
                .counterPay(100)
                .sumTotal(new BigDecimal("10000.00"))
                .build());
        return order;
    }

    private OrderStatus status(String title) {
        OrderStatus status = new OrderStatus();
        status.setId(7L);
        status.setTitle(title);
        return status;
    }

    private PaymentCheck stubFinancialRollback(Order order, BigDecimal sum) {
        PaymentCheck check = PaymentCheck.builder()
                .id(2000L + order.getId())
                .orderId(order.getId())
                .companyId(order.getCompany().getId())
                .paymentStatusGuard(order.getStatus().getId())
                .sum(sum)
                .paidAmount(order.getAmount())
                .active(true)
                .build();
        when(orderRepository.findByIdForCounterUpdate(order.getId())).thenReturn(Optional.of(order));
        when(paymentLinkRepository.existsByOrder_IdAndStatusIn(any(), anySet())).thenReturn(false);
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(order.getId())).thenReturn(List.of(check));
        org.mockito.Mockito.lenient().when(
                contractorRewardLedgerService.lockActiveOrderRewardsAndRequireCancellationRepresentable(order.getId()))
                .thenReturn(List.of());
        when(companyService.getCompaniesById(order.getCompany().getId())).thenReturn(order.getCompany());
        return check;
    }
}
