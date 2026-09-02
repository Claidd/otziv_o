package com.hunt.otziv.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.contractor_payments.service.ContractorCompletionRewardService;
import com.hunt.otziv.contractor_payments.service.ContractorRewardLedgerService;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.repository.PaymentCheckRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class PaymentReturnOrderRecoveryServiceTest {

    @Mock
    private PaymentLinkRepository paymentLinkRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusTransitionService orderStatusTransitionService;

    @Mock
    private PaymentLinkService paymentLinkService;

    @Mock
    private ContractorCompletionRewardService contractorCompletionRewardService;

    @Mock
    private PaymentCheckRepository paymentCheckRepository;

    @Mock
    private CompanyService companyService;

    @Mock
    private ContractorRewardLedgerService contractorRewardLedgerService;

    @Mock
    private PaymentIssueReminderService paymentIssueReminderService;

    @Mock
    private CommonInvoiceOrderRepository commonInvoiceOrderRepository;

    @Test
    void fullRefundReopensOrderWithoutPreparingReplacementInsideStatusTransaction() throws Exception {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setConfirmedAmountKopecks(10_000L);
        stubLockedReturn(link);
        stubFinancials(order, new BigDecimal("100.00"));

        PaymentReturnOrderRecoveryService service = service();

        assertEquals(
                Optional.of(42L),
                service.reopenAfterFullReturn(new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED))
        );

        verify(contractorCompletionRewardService)
                .deactivateOrderPaymentAccruals(42L, "provider_full_return:REFUNDED");
        verify(orderStatusTransitionService).changeStatusAfterPaymentReturn(42L, "Напоминание");
        verify(paymentLinkService, never()).createForOrder(42L);
    }

    @Test
    void fullRefundFlushesInactiveCheckBeforeStatusAndRollsBackExactPaymentTotals() throws Exception {
        Company orderCompany = Company.builder()
                .id(9L)
                .counterPay(20)
                .sumTotal(new BigDecimal("2000.00"))
                .build();
        Order order = order(42L, "Оплачено");
        order.setAmount(5);
        order.setCompany(orderCompany);
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setConfirmedAmountKopecks(120_000L);
        PaymentCheck check = PaymentCheck.builder()
                .id(81L)
                .orderId(42L)
                .companyId(9L)
                .paymentStatusGuard(order.getStatus().getId())
                .sum(new BigDecimal("1200.00"))
                .paidAmount(6)
                .paymentLinkId(7L)
                .active(true)
                .build();

        stubLockedReturn(link);
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(42L)).thenReturn(List.of(check));
        when(companyService.getCompaniesById(9L)).thenReturn(orderCompany);

        assertEquals(Optional.of(42L), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        assertFalse(check.isActive());
        assertEquals(14, orderCompany.getCounterPay());
        assertEquals(0, new BigDecimal("800.00").compareTo(orderCompany.getSumTotal()));
        var ordered = inOrder(
                paymentCheckRepository,
                orderRepository,
                contractorCompletionRewardService,
                companyService,
                orderStatusTransitionService
        );
        ordered.verify(paymentCheckRepository).save(check);
        ordered.verify(orderRepository).flush();
        ordered.verify(contractorCompletionRewardService)
                .deactivateOrderPaymentAccruals(42L, "provider_full_return:REFUNDED");
        ordered.verify(companyService).save(orderCompany);
        ordered.verify(orderStatusTransitionService).changeStatusAfterPaymentReturn(42L, "Напоминание");
    }

    @Test
    void createReplacementPaymentRouteDelegatesAfterStatusTransaction() throws Exception {
        service().createReplacementPaymentRoute(42L);

        verify(paymentLinkService).createForOrder(42L);
    }

    @Test
    void returnDoesNotReopenOrderWhenAnOlderPaymentRemainsConfirmed() throws Exception {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        LocalDateTime returnedAt = LocalDateTime.of(2026, 5, 26, 1, 5);
        link.setPaidAt(returnedAt);
        stubLockedReturn(link);
        stubFinancials(order, new BigDecimal("100.00"));
        when(paymentLinkRepository.existsOtherPaymentBlockingReturn(42L, 7L)).thenReturn(true);

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        verify(contractorCompletionRewardService, never())
                .deactivateOrderPaymentAccruals(42L, "provider_full_return:REFUNDED");
        verify(orderStatusTransitionService, never()).changeStatusAfterPaymentReturn(42L, "Напоминание");
        verify(paymentLinkService, never()).createForOrder(42L);
        assertEquals("MANUAL_RECONCILIATION", link.getReturnRecoveryOutcome());
    }

    @Test
    void historicalReturnDoesNotReopenOrderWhenNewerManualPaidClosureExists() throws Exception {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        LocalDateTime returnedAt = LocalDateTime.of(2026, 5, 26, 1, 5);
        link.setPaidAt(returnedAt);
        stubLockedReturn(link);
        stubFinancials(order, new BigDecimal("100.00"));
        when(paymentLinkRepository.existsNewerManualPaidClosure(42L, 7L, returnedAt)).thenReturn(true);

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        verify(orderStatusTransitionService, never()).changeStatusAfterPaymentReturn(42L, "Напоминание");
        verify(paymentLinkService, never()).createForOrder(42L);
        assertEquals("MANUAL_RECONCILIATION", link.getReturnRecoveryOutcome());
    }

    @Test
    void disabledPaymentLinksStillLeaveReminderForRetry() throws Exception {
        when(paymentLinkService.createForOrder(42L)).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Платежные ссылки выключены"
        ));

        assertDoesNotThrow(() -> service().createReplacementPaymentRoute(42L));

        verify(paymentLinkService).createForOrder(42L);
    }

    @Test
    void unresolvedManualTaskRouteStillLeavesReminderForRetry() throws Exception {
        when(paymentLinkService.createForOrder(42L)).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Получатель платёжного задания не привязан; оплату нужно сверить вручную"
        ));

        assertDoesNotThrow(() -> service().createReplacementPaymentRoute(42L));

        verify(paymentLinkService).createForOrder(42L);
    }

    @Test
    void existingBankPaymentStillLeavesReminderForRetry() throws Exception {
        when(paymentLinkService.createForOrder(42L)).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "У заказа уже есть созданный банковский платеж. Проверьте его статус перед новым счетом."
        ));

        assertDoesNotThrow(() -> service().createReplacementPaymentRoute(42L));

        verify(paymentLinkService).createForOrder(42L);
    }

    @Test
    void disabledPaymentLinksStillCommitReminderStateBeforeRouteRetry() throws Exception {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setConfirmedAmountKopecks(10_000L);
        stubLockedReturn(link);
        stubFinancials(order, new BigDecimal("100.00"));

        assertEquals(Optional.of(42L), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        verify(orderStatusTransitionService).changeStatusAfterPaymentReturn(42L, "Напоминание");
        verify(paymentLinkService, never()).createForOrder(42L);
    }

    @Test
    void retryWithAppliedMarkerAfterOrderAlreadyReopenedIsIdempotent() throws Exception {
        Order order = order(42L, "Напоминание");
        PaymentLink link = link(7L, PaymentLinkStatus.REVERSED, order);
        link.setPaidAt(java.time.LocalDateTime.now());
        link.setReturnRecoveryProcessedAt(LocalDateTime.now());
        link.setReturnRecoveryOutcome("APPLIED");
        link.setReturnRecoveryPaymentCheckId(81L);
        stubLockedReturn(link);

        assertEquals(Optional.of(42L), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REVERSED)));

        verify(orderStatusTransitionService, never()).changeStatusAfterPaymentReturn(42L, "Напоминание");
        verify(paymentLinkService, never()).createForOrder(42L);
        verify(paymentCheckRepository, never()).findByOrderIdAndActiveTrue(42L);
        verify(companyService, never()).getCompaniesById(9L);
        verify(contractorCompletionRewardService, never())
                .deactivateOrderPaymentAccruals(42L, "provider_full_return:REVERSED");
    }

    @Test
    void retryAfterSuccessfulReturnDoesNotRollbackFinancialsTwice() throws Exception {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setConfirmedAmountKopecks(10_000L);
        PaymentCheck check = PaymentCheck.builder()
                .id(81L)
                .orderId(42L)
                .companyId(order.getCompany().getId())
                .paymentStatusGuard(order.getStatus().getId())
                .sum(new BigDecimal("100.00"))
                .paidAmount(5)
                .paymentLinkId(7L)
                .active(true)
                .build();
        stubLockedReturn(link);
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(42L)).thenReturn(List.of(check));
        org.mockito.Mockito.lenient()
                .when(companyService.getCompaniesById(order.getCompany().getId()))
                .thenReturn(order.getCompany());

        var claim = new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                7L, PaymentLinkStatus.REFUNDED);
        assertEquals(Optional.of(42L), service().reopenAfterFullReturn(claim));
        order.setStatus(status("Напоминание"));
        assertEquals(Optional.of(42L), service().reopenAfterFullReturn(claim));

        verify(paymentCheckRepository, times(1)).findByOrderIdAndActiveTrue(42L);
        verify(paymentCheckRepository, times(1)).save(check);
        verify(orderRepository, times(1)).flush();
        verify(contractorCompletionRewardService, times(1))
                .deactivateOrderPaymentAccruals(42L, "provider_full_return:REFUNDED");
        verify(orderStatusTransitionService, times(1)).changeStatusAfterPaymentReturn(42L, "Напоминание");
    }

    @Test
    void historicalReturnCannotRollbackNewPaidCycle() throws Exception {
        Order order = order(42L, "Оплачено");
        PaymentLink returnedLink = link(7L, PaymentLinkStatus.REFUNDED, order);
        returnedLink.setConfirmedAmountKopecks(10_000L);
        PaymentCheck newCycleCheck = PaymentCheck.builder()
                .id(92L)
                .orderId(42L)
                .companyId(9L)
                .paymentStatusGuard(order.getStatus().getId())
                .sum(new BigDecimal("100.00"))
                .paidAmount(5)
                .paymentLinkId(8L)
                .active(true)
                .build();
        stubLockedReturn(returnedLink);
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(42L)).thenReturn(List.of(newCycleCheck));

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        assertTrue(newCycleCheck.isActive());
        assertEquals("STALE_PAYMENT_CYCLE", returnedLink.getReturnRecoveryOutcome());
        assertEquals(92L, returnedLink.getReturnRecoveryPaymentCheckId());
        verify(paymentCheckRepository, never()).save(newCycleCheck);
        verifyNoInteractions(companyService);
        verify(contractorCompletionRewardService, never())
                .deactivateOrderPaymentAccruals(42L, "provider_full_return:REFUNDED");
        verify(orderStatusTransitionService, never()).changeStatusAfterPaymentReturn(42L, "Напоминание");
    }

    @Test
    void appliedReturnMarkerCannotTouchNewPaidCycleDuringPostActionRetry() throws Exception {
        Order order = order(42L, "Оплачено");
        PaymentLink returnedLink = link(7L, PaymentLinkStatus.REFUNDED, order);
        returnedLink.setReturnRecoveryProcessedAt(LocalDateTime.now());
        returnedLink.setReturnRecoveryPaymentCheckId(81L);
        returnedLink.setReturnRecoveryOutcome("APPLIED");
        stubLockedReturn(returnedLink);

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        verify(paymentCheckRepository, never()).findByOrderIdAndActiveTrue(42L);
        verifyNoInteractions(companyService);
        verify(orderStatusTransitionService, never()).changeStatusAfterPaymentReturn(42L, "Напоминание");
    }

    @Test
    void legacyCheckWithoutAmountSnapshotIsMarkedForManualReconciliation() {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setConfirmedAmountKopecks(10_000L);
        PaymentCheck legacyCheck = PaymentCheck.builder()
                .id(81L)
                .orderId(42L)
                .companyId(9L)
                .paymentStatusGuard(order.getStatus().getId())
                .sum(new BigDecimal("100.00"))
                .paymentLinkId(7L)
                .active(true)
                .build();
        stubLockedReturn(link);
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(42L)).thenReturn(List.of(legacyCheck));

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        assertTrue(legacyCheck.isActive());
        assertEquals("MANUAL_RECONCILIATION", link.getReturnRecoveryOutcome());
        assertTrue(link.getLastError().startsWith("payment_return_manual_reconciliation:"));
        verify(paymentIssueReminderService).ensureOrderIssuePersisted(
                eq(order),
                eq(PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION),
                eq(7L),
                anyString(),
                anyString()
        );
        verify(paymentCheckRepository, never()).save(legacyCheck);
        verifyNoInteractions(companyService);
    }

    @Test
    void reminderWithoutDurableMarkerDoesNotAssumeRecoveryWasApplied() throws Exception {
        Order order = order(42L, "Напоминание");
        PaymentLink link = link(7L, PaymentLinkStatus.REVERSED, order);
        link.setPaidAt(LocalDateTime.now());
        stubLockedReturn(link);

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REVERSED)));

        assertEquals("MANUAL_RECONCILIATION", link.getReturnRecoveryOutcome());
        verify(paymentCheckRepository, never()).findByOrderIdAndActiveTrue(42L);
        verify(orderStatusTransitionService, never()).changeStatusAfterPaymentReturn(42L, "Напоминание");
    }

    @Test
    void reminderWithLocalUnsettledCancellationDoesNotCreateFalseManualReconciliation() {
        Order order = order(42L, "Напоминание");
        PaymentLink link = link(7L, PaymentLinkStatus.CANCELED, order);
        stubLockedReturn(link);

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.CANCELED)));

        assertEquals(null, link.getReturnRecoveryOutcome());
        assertEquals(null, link.getReturnRecoveryProcessedAt());
        verifyNoInteractions(paymentIssueReminderService);
        verify(paymentCheckRepository, never()).findByOrderIdAndActiveTrue(42L);
    }

    @Test
    void partialReturnDoesNotCreateAnotherPaymentCycle() {
        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.PARTIAL_REFUNDED)));
        verify(paymentLinkRepository, never()).findByIdForUpdate(7L);
        verify(paymentLinkService, never()).createForOrder(42L);
    }

    @Test
    void cancellationWithoutSettledEvidenceDoesNotReopenOrder() throws Exception {
        Order order = order(42L, "Выставлен счет");
        PaymentLink link = link(7L, PaymentLinkStatus.CANCELED, order);
        stubLockedReturn(link);

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.CANCELED)));
        verify(orderStatusTransitionService, never()).changeStatusAfterPaymentReturn(42L, "Напоминание");
        verify(paymentLinkService, never()).createForOrder(42L);
    }

    @Test
    void paidExactCycleWithoutLinkEvidenceRequiresManualReconciliation() {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.CANCELED, order);
        stubLockedReturn(link);
        PaymentCheck check = PaymentCheck.builder()
                .id(81L)
                .orderId(42L)
                .companyId(9L)
                .paymentStatusGuard(order.getStatus().getId())
                .sum(new BigDecimal("100.00"))
                .paidAmount(5)
                .paymentLinkId(7L)
                .active(true)
                .build();
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(42L)).thenReturn(List.of(check));

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.CANCELED)));

        assertEquals("MANUAL_RECONCILIATION", link.getReturnRecoveryOutcome());
        verify(paymentIssueReminderService).ensureOrderIssuePersisted(
                eq(order),
                eq(PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION),
                eq(7L),
                anyString(),
                anyString());
        verify(contractorCompletionRewardService, never())
                .deactivateOrderPaymentAccruals(42L, "provider_full_return:CANCELED");
    }

    @Test
    void paidReturnWithoutExactlyOneActiveCheckFailsClosed() throws Exception {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setConfirmedAmountKopecks(10_000L);
        stubLockedReturn(link);
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(42L)).thenReturn(List.of());

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        assertEquals("MANUAL_RECONCILIATION", link.getReturnRecoveryOutcome());
        verify(contractorCompletionRewardService, never())
                .deactivateOrderPaymentAccruals(42L, "provider_full_return:REFUNDED");
        verifyNoInteractions(companyService);
        verify(orderStatusTransitionService, never()).changeStatusAfterPaymentReturn(42L, "Напоминание");
    }

    @Test
    void preCutoverRewardBlocksReturnBeforeAnyFinancialRollback() {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setConfirmedAmountKopecks(10_000L);
        stubLockedReturn(link);
        PaymentCheck check = PaymentCheck.builder()
                .id(81L)
                .orderId(42L)
                .companyId(order.getCompany().getId())
                .paymentStatusGuard(order.getStatus().getId())
                .sum(new BigDecimal("100.00"))
                .paidAmount(5)
                .paymentLinkId(7L)
                .active(true)
                .build();
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(42L)).thenReturn(List.of(check));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "opening balance"))
                .when(contractorRewardLedgerService)
                .lockActiveOrderRewardsAndRequireCancellationRepresentable(42L);

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        assertEquals("MANUAL_RECONCILIATION", link.getReturnRecoveryOutcome());
        verify(paymentCheckRepository, never()).save(check);
        verify(contractorCompletionRewardService, never())
                .deactivateOrderPaymentAccruals(42L, "provider_full_return:REFUNDED");
        verifyNoInteractions(companyService);
    }

    @Test
    void pendingAlternativeDoesNotBlockRollbackOfExactReturnedCycle() throws Exception {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setConfirmedAmountKopecks(10_000L);
        stubLockedReturn(link);
        PaymentCheck check = stubFinancials(order, new BigDecimal("100.00"));
        when(paymentLinkRepository.existsOtherPaymentBlockingReturn(42L, 7L)).thenReturn(false);

        assertEquals(Optional.of(42L), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        assertEquals("APPLIED", link.getReturnRecoveryOutcome());
        assertEquals(check.getId(), link.getReturnRecoveryPaymentCheckId());
        verify(paymentCheckRepository).save(check);
    }

    @Test
    void commonInvoiceMembershipForcesManualWithoutTouchingStandaloneFinancials() {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setConfirmedAmountKopecks(10_000L);
        stubLockedReturn(link);
        PaymentCheck check = stubFinancials(order, new BigDecimal("100.00"));
        when(commonInvoiceOrderRepository.existsByOrder_Id(42L)).thenReturn(true);

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        assertEquals("MANUAL_RECONCILIATION", link.getReturnRecoveryOutcome());
        assertTrue(check.isActive());
        assertEquals(100, order.getCompany().getCounterPay());
        assertEquals(0, new BigDecimal("10000.00").compareTo(order.getCompany().getSumTotal()));
        verify(paymentCheckRepository, never()).save(check);
        verify(contractorRewardLedgerService, never())
                .lockActiveOrderRewardsAndRequireCancellationRepresentable(42L);
        verify(contractorCompletionRewardService, never())
                .deactivateOrderPaymentAccruals(42L, "provider_full_return:REFUNDED");
        verify(companyService, never()).save(order.getCompany());
        verify(paymentIssueReminderService).ensureOrderIssuePersisted(
                eq(order),
                eq(PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION),
                eq(7L),
                anyString(),
                anyString()
        );
    }

    @Test
    void partialMarkerTupleFailsClosedBeforeFinancialLookup() {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setReturnRecoveryOutcome("APPLIED");
        link.setReturnRecoveryPaymentCheckId(81L);
        stubLockedReturn(link);

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        assertEquals("MANUAL_RECONCILIATION", link.getReturnRecoveryOutcome());
        verify(paymentCheckRepository, never()).findByOrderIdAndActiveTrue(42L);
        verify(paymentIssueReminderService).ensureOrderIssuePersisted(
                eq(order),
                eq(PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION),
                eq(7L),
                anyString(),
                anyString()
        );
    }

    @Test
    void missingCompanyIsDeterministicManualReconciliation() {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setConfirmedAmountKopecks(10_000L);
        stubLockedReturn(link);
        PaymentCheck check = PaymentCheck.builder()
                .id(81L)
                .orderId(42L)
                .companyId(9L)
                .paymentStatusGuard(order.getStatus().getId())
                .sum(new BigDecimal("100.00"))
                .paidAmount(5)
                .paymentLinkId(7L)
                .active(true)
                .build();
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(42L)).thenReturn(List.of(check));
        when(companyService.getCompaniesById(9L)).thenThrow(new UsernameNotFoundException("missing"));

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        assertEquals("MANUAL_RECONCILIATION", link.getReturnRecoveryOutcome());
        assertTrue(check.isActive());
        verify(paymentCheckRepository, never()).save(check);
        verify(contractorCompletionRewardService, never())
                .deactivateOrderPaymentAccruals(42L, "provider_full_return:REFUNDED");
    }

    @Test
    void manualMarkerIsNotAcceptedUntilReminderIsPersisted() {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setConfirmedAmountKopecks(10_000L);
        PaymentCheck legacyCheck = PaymentCheck.builder()
                .id(81L)
                .orderId(42L)
                .companyId(9L)
                .paymentStatusGuard(order.getStatus().getId())
                .sum(new BigDecimal("100.00"))
                .paymentLinkId(7L)
                .active(true)
                .build();
        stubLockedReturn(link);
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(42L)).thenReturn(List.of(legacyCheck));
        doThrow(new IllegalStateException("reminder storage unavailable"))
                .when(paymentIssueReminderService)
                .ensureOrderIssuePersisted(eq(order), anyString(), eq(7L), anyString(), anyString());

        assertThrows(IllegalStateException.class, () -> service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        verify(paymentCheckRepository, never()).save(legacyCheck);
    }

    @Test
    void manuallyResolvedMarkerIsTerminalNoop() {
        Order order = order(42L, "Напоминание");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setReturnRecoveryProcessedAt(LocalDateTime.now());
        link.setReturnRecoveryOutcome("ACCEPTED_NOOP");
        link.setReturnRecoveryResolvedAt(LocalDateTime.now());
        link.setReturnRecoveryResolvedBy("owner@test");
        link.setReturnRecoveryResolutionReason("Сверено по выписке");
        stubLockedReturn(link);

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        verifyNoInteractions(paymentIssueReminderService);
        verify(paymentCheckRepository, never()).findByOrderIdAndActiveTrue(42L);
    }

    @Test
    void manuallyAppliedMarkerReturnsOrderForDurableWorkerFollowUp() {
        Order order = order(42L, "Напоминание");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setReturnRecoveryProcessedAt(LocalDateTime.now());
        link.setReturnRecoveryPaymentCheckId(81L);
        link.setReturnRecoveryOutcome("APPLIED_MANUALLY");
        link.setReturnRecoveryResolvedAt(LocalDateTime.now());
        link.setReturnRecoveryResolvedBy("owner@test");
        link.setReturnRecoveryResolutionReason("Откат подтвержден по выписке");
        stubLockedReturn(link);
        when(paymentIssueReminderService.hasOpenOrderIssue(
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                7L)).thenReturn(true);

        assertEquals(Optional.of(42L), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        verify(paymentLinkService, never()).createForOrder(42L);
    }

    @Test
    void manuallyAppliedMarkerStillRunsDurableFollowUpAfterOrderMovedToPay() {
        Order order = order(42L, "Выставлен счет");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setReturnRecoveryProcessedAt(LocalDateTime.now());
        link.setReturnRecoveryPaymentCheckId(81L);
        link.setReturnRecoveryOutcome("APPLIED_MANUALLY");
        link.setReturnRecoveryResolvedAt(LocalDateTime.now());
        link.setReturnRecoveryResolvedBy("owner@test");
        link.setReturnRecoveryResolutionReason("Откат подтвержден по выписке");
        stubLockedReturn(link);
        when(paymentIssueReminderService.hasOpenOrderIssue(
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                7L)).thenReturn(true);

        assertEquals(Optional.of(42L), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        verify(paymentIssueReminderService, never()).resolveOrderIssueInCurrentTransaction(
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                7L);
    }

    @Test
    void duplicateProviderObservationAfterManualFollowUpWasClosedIsNoop() {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setReturnRecoveryProcessedAt(LocalDateTime.now());
        link.setReturnRecoveryPaymentCheckId(81L);
        link.setReturnRecoveryOutcome("APPLIED_MANUALLY");
        link.setReturnRecoveryResolvedAt(LocalDateTime.now());
        link.setReturnRecoveryResolvedBy("owner@test");
        link.setReturnRecoveryResolutionReason("Откат подтвержден по выписке");
        stubLockedReturn(link);
        when(paymentIssueReminderService.hasOpenOrderIssue(
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                7L)).thenReturn(false);

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));
        verify(paymentIssueReminderService, never()).resolveOrderIssueInCurrentTransaction(
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                7L);
    }

    @Test
    void newPaidCycleSupersedesStaleOpenManualFollowUpIssue() {
        Order order = order(42L, "Оплачено");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setReturnRecoveryProcessedAt(LocalDateTime.now());
        link.setReturnRecoveryPaymentCheckId(81L);
        link.setReturnRecoveryOutcome("APPLIED_MANUALLY");
        link.setReturnRecoveryResolvedAt(LocalDateTime.now());
        link.setReturnRecoveryResolvedBy("owner@test");
        link.setReturnRecoveryResolutionReason("Откат подтвержден по выписке");
        stubLockedReturn(link);
        when(paymentIssueReminderService.hasOpenOrderIssue(
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                7L)).thenReturn(true);

        assertEquals(Optional.empty(), service().reopenAfterFullReturn(
                new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                        7L, PaymentLinkStatus.REFUNDED)));

        verify(paymentIssueReminderService).resolveOrderIssueInCurrentTransaction(
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                7L);
    }

    @Test
    void workerCompletionClosesManualIssueOnlyForAppliedManualMarker() {
        Order order = order(42L, "Напоминание");
        PaymentLink link = link(7L, PaymentLinkStatus.REFUNDED, order);
        link.setReturnRecoveryProcessedAt(LocalDateTime.now());
        link.setReturnRecoveryPaymentCheckId(81L);
        link.setReturnRecoveryOutcome("APPLIED_MANUALLY");
        link.setReturnRecoveryResolvedAt(LocalDateTime.now());
        link.setReturnRecoveryResolvedBy("owner@test");
        link.setReturnRecoveryResolutionReason("Откат подтвержден по выписке");
        stubLockedReturn(link);

        service().completeManualReturnFollowUp(7L);

        verify(paymentIssueReminderService).resolveOrderIssueInCurrentTransaction(
                PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                7L);
    }

    private PaymentReturnOrderRecoveryService service() {
        return new PaymentReturnOrderRecoveryService(
                paymentLinkRepository,
                orderRepository,
                orderStatusTransitionService,
                paymentLinkService,
                contractorCompletionRewardService,
                paymentCheckRepository,
                companyService,
                contractorRewardLedgerService,
                paymentIssueReminderService,
                commonInvoiceOrderRepository
        );
    }

    private Order order(Long id, String statusTitle) {
        Order order = new Order();
        order.setId(id);
        order.setStatus(OrderStatus.builder().id(7L).title(statusTitle).build());
        order.setAmount(5);
        order.setCompany(Company.builder()
                .id(9L)
                .counterPay(100)
                .sumTotal(new BigDecimal("10000.00"))
                .build());
        return order;
    }

    private void stubLockedReturn(PaymentLink link) {
        Long linkId = link.getId();
        Long orderId = link.getOrder().getId();
        when(paymentLinkRepository.findOrderIdById(linkId)).thenReturn(Optional.of(orderId));
        when(orderRepository.findByIdForCounterUpdate(orderId)).thenReturn(Optional.of(link.getOrder()));
        when(paymentLinkRepository.findByIdForUpdate(linkId)).thenReturn(Optional.of(link));
    }

    private PaymentCheck stubFinancials(Order order, BigDecimal sum) {
        PaymentCheck check = PaymentCheck.builder()
                .id(81L)
                .orderId(order.getId())
                .companyId(order.getCompany().getId())
                .paymentStatusGuard(order.getStatus().getId())
                .sum(sum)
                .paidAmount(order.getAmount())
                .paymentLinkId(7L)
                .active(true)
                .build();
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(order.getId())).thenReturn(List.of(check));
        org.mockito.Mockito.lenient()
                .when(companyService.getCompaniesById(order.getCompany().getId()))
                .thenReturn(order.getCompany());
        return check;
    }

    private PaymentLink link(Long id, PaymentLinkStatus status, Order order) {
        PaymentLink link = new PaymentLink();
        link.setId(id);
        link.setStatus(status);
        link.setOrder(order);
        return link;
    }

    private OrderStatus status(String title) {
        return OrderStatus.builder().title(title).build();
    }
}
