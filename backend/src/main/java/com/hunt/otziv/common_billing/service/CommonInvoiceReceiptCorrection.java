package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.api.ManualReceiptInvoiceDebt;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.p_products.api.ManualReceiptOrderDebt;
import java.security.Principal;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CommonInvoiceReceiptCorrection implements ManualReceiptInvoiceDebt {
    private final CommonInvoiceDeliveryService delivery;
    private final CommonInvoiceOrderRepository items;
    private final ManualReceiptOrderDebt orders;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reopen(long id, String evidence, long amount, Principal actor, String reason) {
        var invoice = delivery.lockedInvoiceAfterStandalonePaymentPrelude(id).invoice();
        var positions = items.findByInvoiceIdWithOrders(id);
        if (invoice.getStatus() != CommonInvoiceStatus.PAID || invoice.getManualConfirmedAt() == null || invoice.getPaperBankDocumentId() != null
                || !"MANUAL".equals(invoice.getPaymentMethod()) || invoice.getPaidKopecks() != amount || positions.isEmpty()
                || positions.stream().anyMatch(item -> !item.isPaid() || !Objects.equals(evidence,item.getActualPaymentEvidenceReference()))
                || positions.stream().mapToLong(item -> item.getAmountKopecks()).sum() != amount)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состав счёта или происхождение оплаты изменились");
        for (var item : positions) {
            orders.reopen(item.orderId(),null,item.getAmountKopecks(),true,actor,reason);
        }
        // Reversing order rewards flushes and clears the persistence context.
        invoice = delivery.lockedInvoice(id).orElseThrow();
        for (var item : items.findByInvoiceIdWithOrders(id)) {
            item.setPaid(false);
            item.setPaidAt(null);
            item.setUnpaid(true);
            item.setPaymentMethod(null);
            item.setActualPaymentEvidenceReference(null);
            item.setManualPaidBy(null);
            item.setManualPaymentComment(null);
            item.setManualPaymentReceiptUrl(null);
        }
        CommonInvoiceRouteState.clearPaymentRoute(invoice);
        invoice.setPaidKopecks(0);
        invoice.setPaidAt(null);
        invoice.setClosedAt(null);
        invoice.setClosedBy(null);
        invoice.setCloseReason(null);
        // Restore a payable state on the same public URL. An already delivered bill keeps its delivery history.
        invoice.setStatus(invoice.getSentAt() == null ? CommonInvoiceStatus.READY : CommonInvoiceStatus.INVOICED);
        invoice.setPaymentMethod(null);
        invoice.setManualConfirmedAt(null);
        invoice.setManualPaidBy(null);
        invoice.setManualPaymentComment(null);
        invoice.setManualPaymentReceiptUrl(null);
        invoice.setClientReportedAt(null);
        invoice.setLastError(null);
    }
}
