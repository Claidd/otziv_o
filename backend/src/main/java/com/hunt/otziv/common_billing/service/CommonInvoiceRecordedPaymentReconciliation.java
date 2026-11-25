package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRequest;
import com.hunt.otziv.common_billing.dto.ManualPaymentConfirmationRequest;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.z_zp.service.PaymentCheckService;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Links an administrator-verified receipt to existing order accounting without posting it twice. */
@Service
@RequiredArgsConstructor
public class CommonInvoiceRecordedPaymentReconciliation {
    private final CommonInvoiceSettlementService settlement;
    private final CommonInvoiceDeliveryService delivery;
    private final CommonInvoiceManualPaymentWorkflow manualPayments;
    private final CommonManualPaymentAttributionCoordinator attribution;
    private final CommonInvoiceOrderRepository itemsRepository;
    private final CommonInvoiceRepository invoiceRepository;
    private final CommonInvoicePaymentRefRepository bankRefs;
    private final CommonInvoiceDetailsAssembler details;
    private final PaymentCheckService checks;

    @Transactional
    public CommonInvoiceDetailsResponse reconcile(long invoiceId, CommonManualPaymentAttributionRequest request,
            Principal principal) {
        var invoice = delivery.lockedInvoiceAfterStandalonePaymentPrelude(invoiceId).invoice();
        manualPayments.ensureCommonInvoiceVisibleForCurrentUser(invoice);
        var items = itemsRepository.findByInvoiceIdWithOrders(invoiceId);
        if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
            if (!attribution.replayIfRecorded(invoice, items, request, principal)) throw conflict();
            return details.invoiceDetails(invoice, items);
        }
        manualPayments.ensureCommonInvoiceNotNeedsAttention(invoice);
        if (invoice.getStatus() != CommonInvoiceStatus.BAN || !"BAN".equals(invoice.getCloseReason())
                || invoice.getClosedAt() == null || invoice.getPaidKopecks() != 0
                || invoice.getManualConfirmedAt() != null || invoice.getPaperBankDocumentId() != null
                || invoice.isPaperInvoiceAutomationHold() || invoice.isPaperInvoiceReviewHold()
                || invoice.getBadReviewBankRefId() != null || items.isEmpty()
                || request.effectiveAt() == null || request.effectiveAt().isAfter(LocalDateTime.now())
                || request.effectiveAt().isBefore(invoice.getClosedAt())
                || !Boolean.TRUE.equals(request.paymentReceived())
                || !Boolean.TRUE.equals(request.finalAccountingAcknowledged())) throw conflict();
        manualPayments.ensureCommonPaymentRefsSafeForManualCard(bankRefs.findByInvoiceIdForUpdate(invoiceId));
        var orderIds = new HashSet<Long>();
        long total = 0;
        for (var item : items) {
            var order = item.getOrder();
            if (!item.isActiveMembership() || item.isPaid() || item.getSourcePaymentLinkId() != null
                    || item.getActualPaymentEvidenceReference() != null || order == null || order.getId() == null
                    || !orderIds.add(order.getId()) || !settlement.isOrderPaid(order)) throw conflict();
            if (!Objects.equals(order.getPayDay(), request.effectiveAt().toLocalDate())) throw conflict();
            checks.requireSingleRecordedManualPayment(order.getId(), item.getAmountKopecks(),
                    request.effectiveAt().toLocalDate());
            total = Math.addExact(total, item.getAmountKopecks());
        }
        if (total != invoice.getAmountKopecks() || total <= 0
                || request.attributions().stream().mapToLong(row -> row.amountKopecks()).reduce(0L, Math::addExact) != total)
            throw conflict();
        // All order checks already exist. This operation records the actual recipient and repairs only invoice state.
        manualPayments.applyManualPaymentEvidence(invoice, items,
                new ManualPaymentConfirmationRequest(request.reason(), request.receiptUrl()), principal);
        attribution.recordFinalReceipt(invoice, items, total, request, principal);
        String evidence = CommonManualPaymentAttributionCoordinator.evidenceReference(invoiceId, request.idempotencyKey());
        for (var item : items) {
            item.setPaid(true);
            item.setUnpaid(false);
            item.setPaidAt(request.effectiveAt());
            item.setActualPaymentEvidenceReference(evidence);
        }
        invoice.setPaidKopecks(total);
        invoice.setPaidAt(request.effectiveAt());
        settlement.markInvoicePaidClosed(invoice);
        invoice.setLastError(null);
        itemsRepository.saveAll(items);
        invoiceRepository.save(invoice);
        return details.invoiceDetails(invoice, items);
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Сверьте единственные учтённые оплаты всех заказов, сумму счёта, дату поступления и банковские операции. "
                        + "Повторное зачисление не выполнено.");
    }
}
