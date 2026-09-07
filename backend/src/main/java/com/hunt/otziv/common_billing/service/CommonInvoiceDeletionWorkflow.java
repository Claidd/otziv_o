package com.hunt.otziv.common_billing.service;

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
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.p_products.deletion.service.OrderDeletionService;
import com.hunt.otziv.p_products.model.Order;
import java.security.Principal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

/** Deletes an eligible invoice and its locked orders as one atomic command. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceDeletionWorkflow {

    private final CommonInvoiceRecoveryWorkflow invoiceRecoveryWorkflow;

    private final CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;

    private final CommonInvoiceDeliveryService invoiceDelivery;

    private final CommonInvoiceInitializationService invoiceInitialization;

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final CommonInvoicePaymentRefRepository paymentRefRepository;

    private final ObjectProvider<OrderDeletionService> orderDeletionServiceProvider;

    @Transactional
    public void deleteInvoiceWithOrders(Long invoiceId, Principal principal) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        ensureCommonInvoiceCanBeDeleted(invoice, items);
        if (hasFrozenCommonPaymentRoute(invoice) || invoice.getContractorAllocationId() != null || invoice.getClientReportedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нельзя удалить общий счет с уже выданными платежными реквизитами. " + "Переведите его в \"Не оплачено\" или выполните ручную сверку.");
        }
        if (isBadReviewSuccessor(invoice)) {
            deleteUnsentBadReviewSuccessor(invoice, items);
            return;
        }
        if (invoiceRepository.existsBySupersedesInvoice_Id(invoiceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Счет имеет дополнительный цикл. Сначала удалите последний неотправленный цикл");
        }
        List<Long> orderIds = items.stream().map(CommonInvoiceOrder::getOrder).filter(Objects::nonNull).map(Order::getId).filter(Objects::nonNull).distinct().toList();
        // OrderDeletionService deliberately fails closed while an order is a
        // member of a common invoice. Detach every membership only after all
        // delete guards have passed and before delegating to the standalone
        // order deletion path. This method is one transaction, so any child
        // deletion failure restores these rows together with all prior work.
        int detachedLinks = invoiceOrderRepository.deleteByInvoiceId(invoiceId);
        if (detachedLinks != items.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состав общего счета изменился во время удаления. Повторите действие.");
        }
        for (Long orderId : orderIds) {
            boolean deleted = orderDeletionServiceProvider.getObject().deleteOrder(orderId, principal);
            if (!deleted) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось удалить связанный заказ #" + orderId);
            }
        }
        int paymentRefs = paymentRefRepository.deleteByInvoiceId(invoiceId);
        invoiceRepository.deleteById(invoiceId);
        log.info("Удален общий счет {} вместе со связанными заказами: orders={}, detachedLinks={}, paymentRefs={}", invoiceId, orderIds.size(), detachedLinks, paymentRefs);
    }

    Optional<CommonInvoice> lockedInvoice(Long invoiceId) {
        return manualPaymentWorkflow.lockedInvoice(invoiceId);
    }

    void deleteUnsentBadReviewSuccessor(CommonInvoice successor, List<CommonInvoiceOrder> successorItems) {
        invoiceRecoveryWorkflow.deleteUnsentBadReviewSuccessor(successor, successorItems);
    }

    boolean isMessageSendInProgress(String state) {
        return invoiceInitialization.isMessageSendInProgress(state);
    }

    void ensureCommonInvoiceVisibleForCurrentUser(CommonInvoice invoice) {
        manualPaymentWorkflow.ensureCommonInvoiceVisibleForCurrentUser(invoice);
    }

    void ensureCommonInvoiceCanBeDeleted(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null || invoice.getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден");
        }
        String rawOperation = normalize(invoice.getLastError());
        if (isMessageSendInProgress(rawOperation) || PAYMENT_INIT_IN_PROGRESS.equals(rawOperation)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нельзя удалить общий счет во время активной операции");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.PAID || invoice.getStatus() == CommonInvoiceStatus.PARTIALLY_PAID || invoice.getPaidKopecks() > 0 || items.stream().anyMatch(CommonInvoiceOrder::isPaid)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нельзя удалить общий счет, по которому уже была оплата");
        }
        boolean hasCurrentPaymentLink = !normalize(invoice.getPaymentUrl()).isBlank() || !normalize(invoice.getTbankOrderId()).isBlank() || !normalize(invoice.getTbankPaymentId()).isBlank() || !normalize(invoice.getTbankTerminalKey()).isBlank();
        if (hasCurrentPaymentLink || paymentRefRepository.existsByInvoice_Id(invoice.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нельзя удалить общий счет с платежной ссылкой T-Bank. Сначала разберите платеж вручную");
        }
    }

    boolean hasFrozenCommonPaymentRoute(CommonInvoice invoice) {
        return manualPaymentWorkflow.hasFrozenCommonPaymentRoute(invoice);
    }

    boolean isBadReviewSuccessor(CommonInvoice invoice) {
        return invoiceDelivery.isBadReviewSuccessor(invoice);
    }

    String normalize(String value) {
        return manualPaymentWorkflow.normalize(value);
    }
}
