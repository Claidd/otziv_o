package com.hunt.otziv.common_billing.service;

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
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

/** Validates every candidate before atomically approving the invoice's review orders. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceReviewApprovalWorkflow {

    private final CommonInvoiceMembershipWorkflow invoiceMembershipWorkflow;

    private final CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;

    private final CommonInvoiceSettlementService settlementService;

    static final String STATUS_IN_CHECK = "На проверке";

    static final Set<String> REVIEW_APPROVAL_STATUSES = Set.of(STATUS_TO_CHECK, STATUS_IN_CHECK);

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final ObjectProvider<OrderPublicationApprovalService> publicationApprovalServiceProvider;

    CommonInvoiceDetailsResponse invoiceAfterOrderPrelude(Long invoiceId) {
        return manualPaymentWorkflow.invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse approveReviewOrders(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        ensureCommonInvoiceCanChangePositions(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        return approveReviewOrders(invoiceId, items);
    }

    CommonInvoiceDetailsResponse approveReviewOrders(Long invoiceId, List<CommonInvoiceOrder> items) {
        List<CommonInvoiceOrder> candidates = items.stream().filter(item -> item.getOrder() != null && REVIEW_APPROVAL_STATUSES.contains(statusTitle(item.getOrder()))).toList();
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В общем счете нет заказов в статусе \"В проверку\" или \"На проверке\"");
        }
        for (CommonInvoiceOrder item : candidates) {
            publicationApprovalService().validateExistingOrder(item.getOrder().getId());
        }
        for (CommonInvoiceOrder item : candidates) {
            Order order = item.getOrder();
            publicationApprovalService().approveExistingOrder(order.getId(), "invoiceId=" + invoiceId + ";source=approve_all");
        }
        return invoiceAfterOrderPrelude(invoiceId);
    }

    OrderPublicationApprovalService publicationApprovalService() {
        return publicationApprovalServiceProvider.getObject();
    }

    Optional<CommonInvoice> lockedInvoice(Long invoiceId) {
        return manualPaymentWorkflow.lockedInvoice(invoiceId);
    }

    void ensureCommonInvoiceNotNeedsAttention(CommonInvoice invoice) {
        manualPaymentWorkflow.ensureCommonInvoiceNotNeedsAttention(invoice);
    }

    void ensureCommonInvoiceCanChangePositions(CommonInvoice invoice) {
        invoiceMembershipWorkflow.ensureCommonInvoiceCanChangePositions(invoice);
    }

    void ensureCommonInvoiceVisibleForCurrentUser(CommonInvoice invoice) {
        manualPaymentWorkflow.ensureCommonInvoiceVisibleForCurrentUser(invoice);
    }

    String statusTitle(Order order) {
        return settlementService.statusTitle(order);
    }
}
