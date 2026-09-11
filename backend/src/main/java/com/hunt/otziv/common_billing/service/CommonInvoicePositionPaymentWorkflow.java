package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonInvoiceDeletionWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceCheckoutWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceRecoveryWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceReviewApprovalWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceArchiveWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceBoardWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonBillingAccountWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonBillingCompanyReconciliationWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceMembershipWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePaymentRouteWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceManualPaymentWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceTochkaReconciliationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDeliveryService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDetailsAssembler.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceInitializationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceCancellationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePresenter.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceSettlementService.*;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.ManualPaymentConfirmationRequest;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionFlowPolicy;
import com.hunt.otziv.p_products.model.Order;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

/** Confirms one invoice position with attribution, order close and invoice totals in the same transaction. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoicePositionPaymentWorkflow {

    private final CommonInvoiceMembershipWorkflow invoiceMembershipWorkflow;

    private final CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;

    private final CommonInvoiceSettlementService settlementService;

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final ContractorActualPaymentAttributionFlowPolicy actualPaymentAttributionFlowPolicy;

    CommonInvoiceDetailsResponse invoiceAfterOrderPrelude(Long invoiceId) {
        return manualPaymentWorkflow.invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse markOrderPaid(Long invoiceId, Long orderId, ManualPaymentConfirmationRequest request, Principal principal) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        actualPaymentAttributionFlowPolicy.requireLegacyFlowLocked();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureGenericConfirmationDoesNotUseContractorSource(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        ensureCommonInvoiceCanChangePositions(invoice);
        CommonInvoiceOrder item = invoiceOrderRepository.findByOrderIdWithInvoice(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден в общем счете"));
        if (!invoice.getId().equals(item.getInvoice().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ относится к другому общему счету");
        }
        applyManualPaymentEvidence(invoice, item, request, principal);
        try {
            closeOrderAsPaidWithoutNextOrder(item.getOrder());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ не удалось закрыть как оплаченный", e);
        }
        item.setPaid(true);
        item.setUnpaid(false);
        item.setPaidAt(LocalDateTime.now());
        invoiceOrderRepository.save(item);
        recalculateInvoice(invoice);
        closePaidIfAllItemsPaid(invoice);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    CommonInvoiceDetailsResponse markOrderPaid(Long invoiceId, Long orderId) {
        return markOrderPaid(invoiceId, orderId, new ManualPaymentConfirmationRequest("Внутреннее подтверждение", ""), () -> "system");
    }

    /**
     * Establishes the same lock order used by standalone payment mutations:
     * Order aggregates, their PaymentLink rows, and only then the common invoice.
     * Membership is checked again while all locks are held so a pre-lock snapshot
     * can never authorize or reconcile a different invoice composition.
     */
    LockedInvoicePaymentPrelude lockedInvoiceAfterStandalonePaymentPrelude(Long invoiceId) {
        return manualPaymentWorkflow.lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
    }

    void ensureGenericConfirmationDoesNotUseContractorSource(CommonInvoice invoice) {
        manualPaymentWorkflow.ensureGenericConfirmationDoesNotUseContractorSource(invoice);
    }

    void closePaidIfAllItemsPaid(CommonInvoice invoice) {
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        if (items.stream().allMatch(CommonInvoiceOrder::isPaid)) {
            closePaidInvoice(invoice, items);
        } else {
            invoice.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
            ensurePartialPaymentNextAction(invoice);
            invoiceRepository.save(invoice);
        }
    }

    void ensureCommonInvoiceNotNeedsAttention(CommonInvoice invoice) {
        manualPaymentWorkflow.ensureCommonInvoiceNotNeedsAttention(invoice);
    }

    void ensureCommonInvoiceCanChangePositions(CommonInvoice invoice) {
        invoiceMembershipWorkflow.ensureCommonInvoiceCanChangePositions(invoice);
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        manualPaymentWorkflow.closePaidInvoice(invoice, items);
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> alreadyClosedOrderIds) {
        manualPaymentWorkflow.closePaidInvoice(invoice, items, alreadyClosedOrderIds);
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> alreadyClosedOrderIds, Runnable finalAttribution) {
        manualPaymentWorkflow.closePaidInvoice(invoice, items, alreadyClosedOrderIds, finalAttribution);
    }

    void closeOrderAsPaidWithoutNextOrder(Order order) throws Exception {
        settlementService.closeOrderAsPaidWithoutNextOrder(order);
    }

    void closeOrderAsPaidWithoutNextOrder(Order order, boolean standalonePaymentAlreadyConfirmed) throws Exception {
        settlementService.closeOrderAsPaidWithoutNextOrder(order, standalonePaymentAlreadyConfirmed);
    }

    void recalculateInvoice(CommonInvoice invoice) {
        settlementService.recalculateInvoice(invoice);
    }

    void recalculateInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.recalculateInvoice(invoice, items);
    }

    void ensurePartialPaymentNextAction(CommonInvoice invoice) {
        settlementService.ensurePartialPaymentNextAction(invoice);
    }

    void ensureCommonInvoiceVisibleForCurrentUser(CommonInvoice invoice) {
        manualPaymentWorkflow.ensureCommonInvoiceVisibleForCurrentUser(invoice);
    }

    void applyManualPaymentEvidence(CommonInvoice invoice, List<CommonInvoiceOrder> items, ManualPaymentConfirmationRequest request, Principal principal) {
        manualPaymentWorkflow.applyManualPaymentEvidence(invoice, items, request, principal);
    }

    void applyManualPaymentEvidence(CommonInvoice invoice, CommonInvoiceOrder item, ManualPaymentConfirmationRequest request, Principal principal) {
        manualPaymentWorkflow.applyManualPaymentEvidence(invoice, item, request, principal);
    }

    void applyManualPaymentEvidence(CommonInvoiceOrder item, ManualPaymentConfirmationRequest request, String actor) {
        manualPaymentWorkflow.applyManualPaymentEvidence(item, request, actor);
    }
}
