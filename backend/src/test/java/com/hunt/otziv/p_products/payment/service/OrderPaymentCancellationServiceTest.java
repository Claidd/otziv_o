package com.hunt.otziv.p_products.payment.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
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
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.z_zp.repository.PaymentCheckRepository;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import com.hunt.otziv.z_zp.model.Zp;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
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
    @Mock private ZpRepository zpRepository;
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

        when(orderRepository.findByIdForMutation(10L)).thenReturn(Optional.of(source));
        when(paymentLinkRepository.existsByOrder_IdAndStatusIn(any(), anySet())).thenReturn(false);
        when(nextOrderRequestRepository.findBySourceOrderId(10L)).thenReturn(Optional.of(request));
        when(commonBillingService.detachOrderForDeletion(20L)).thenReturn(true);
        when(orderDeletionService.deleteOrder(any(), any())).thenReturn(true);
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(10L)).thenReturn(List.of());
        when(zpRepository.findByOrderIdAndActiveTrue(10L)).thenReturn(List.of());
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

        when(orderRepository.findByIdForMutation(11L)).thenReturn(Optional.of(source));
        when(paymentLinkRepository.existsByOrder_IdAndStatusIn(any(), anySet())).thenReturn(false);
        when(nextOrderRequestRepository.findBySourceOrderId(11L)).thenReturn(Optional.empty());
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(11L)).thenReturn(List.of());
        when(zpRepository.findByOrderIdAndActiveTrue(11L)).thenReturn(List.of(reward));
        when(orderStatusService.getOrderStatusByTitle("Напоминание")).thenReturn(reminder);

        service.cancelPayment(11L, principal);

        var financialOrder = inOrder(zpRepository, contractorRewardLedgerService, paymentLinkService);
        financialOrder.verify(zpRepository).saveAll(List.of(reward));
        financialOrder.verify(contractorRewardLedgerService).synchronizeSources(List.of(reward));
        financialOrder.verify(paymentLinkService).createForOrder(11L);
        assertEquals(false, reward.isActive());
    }

    @Test
    void legacyEarnedRewardRemainsActiveWhenClientPaymentIsCanceled() {
        Order source = order(14L, "Оплачено");
        source.setAmount(2);
        Zp legacy = new Zp();
        legacy.setId(72L);
        legacy.setActive(true);
        legacy.setSource(ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST);
        when(orderRepository.findByIdForMutation(14L)).thenReturn(Optional.of(source));
        when(paymentLinkRepository.existsByOrder_IdAndStatusIn(any(), anySet())).thenReturn(false);
        when(nextOrderRequestRepository.findBySourceOrderId(14L)).thenReturn(Optional.empty());
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(14L)).thenReturn(List.of());
        when(zpRepository.findByOrderIdAndActiveTrue(14L)).thenReturn(List.of(legacy));
        when(orderStatusService.getOrderStatusByTitle("Напоминание")).thenReturn(status("Напоминание"));
        when(contractorPaymentRuntimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);

        service.cancelPayment(14L, () -> "admin");

        assertEquals(true, legacy.isActive());
        verify(contractorRewardLedgerService, never()).requireCancellationRepresentable(List.of(legacy));
        verify(contractorRewardLedgerService, never()).synchronizeSources(List.of(legacy));
        verify(contractorCompletionRewardService).migrateLegacyRewardsBeforePaymentCancellation(14L);
    }

    @Test
    void legacyEarnedRewardIsCanceledAsBeforeWhileCompletionAttributionIsOff() {
        Order source = order(15L, "Оплачено");
        source.setAmount(2);
        Zp legacy = new Zp();
        legacy.setId(73L);
        legacy.setActive(true);
        legacy.setSource(ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST);
        when(orderRepository.findByIdForMutation(15L)).thenReturn(Optional.of(source));
        when(paymentLinkRepository.existsByOrder_IdAndStatusIn(any(), anySet())).thenReturn(false);
        when(nextOrderRequestRepository.findBySourceOrderId(15L)).thenReturn(Optional.empty());
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(15L)).thenReturn(List.of());
        when(zpRepository.findByOrderIdAndActiveTrue(15L)).thenReturn(List.of(legacy));
        when(orderStatusService.getOrderStatusByTitle("Напоминание")).thenReturn(status("Напоминание"));

        service.cancelPayment(15L, () -> "admin");

        assertEquals(false, legacy.isActive());
        verify(contractorRewardLedgerService).requireCancellationRepresentable(List.of(legacy));
        verify(contractorRewardLedgerService).synchronizeSources(List.of(legacy));
        verify(contractorCompletionRewardService, never()).migrateLegacyRewardsBeforePaymentCancellation(15L);
    }

    @Test
    void cancelPaymentStopsWhenClientAlreadyReceivedContractorRoute() {
        Order source = order(12L, "Оплачено");
        when(orderRepository.findByIdForMutation(12L)).thenReturn(Optional.of(source));
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
        verify(zpRepository, never()).findByOrderIdAndActiveTrue(12L);
        verify(paymentLinkService, never()).createForOrder(12L);
    }

    @Test
    void cancelPaymentStopsWhenClientReportedCommonInvoicePayment() {
        Order source = order(13L, "Оплачено");
        when(orderRepository.findByIdForMutation(13L)).thenReturn(Optional.of(source));
        when(paymentLinkRepository.existsByOrder_IdAndStatusIn(any(), anySet())).thenReturn(false);
        when(commonBillingService.hasClientReportedPaymentForOrder(13L)).thenReturn(true);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.cancelPayment(13L, () -> "admin")
        );

        assertEquals(409, error.getStatusCode().value());
        verify(paymentCheckRepository, never()).findByOrderIdAndActiveTrue(13L);
        verify(zpRepository, never()).findByOrderIdAndActiveTrue(13L);
    }

    private Order order(Long id, String statusTitle) {
        Order order = new Order();
        order.setId(id);
        order.setStatus(status(statusTitle));
        return order;
    }

    private OrderStatus status(String title) {
        OrderStatus status = new OrderStatus();
        status.setTitle(title);
        return status;
    }
}
