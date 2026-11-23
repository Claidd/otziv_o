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

    /** The actor the Точка bank receipt confirms a paper invoice with: such a payment is real and never undone here. */
    static final String PAPER_BANK_ACTOR = "tochka-bank";

    /**
     * Undoes a paper invoice payment confirmed by mistake: no bank receipt, no recorded recipient. Every order goes back
     * to «Напоминание» through the regular payment reversal (payment check, rewards, company counters), the untouched
     * next orders the payment created are deleted, and the invoice is payable again on the same issued paper invoice.
     */
    @Transactional
    public void reopenOwnerPaperPayment(long id, Principal actor, String reason) {
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.length() < 5 || cleanReason.length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите причину отмены оплаты (5–500 символов)");
        var invoice = delivery.lockedInvoiceAfterStandalonePaymentPrelude(id).invoice();
        var positions = items.findByInvoiceIdWithOrders(id);
        long amount = invoice.getPaidKopecks();
        if (invoice.getStatus() != CommonInvoiceStatus.PAID || invoice.getManualConfirmedAt() == null
                || invoice.getPaperBankDocumentId() != null
                || !CommonInvoiceManualPaymentWorkflow.PAYMENT_METHOD_OWNER_PAPER_INVOICE.equals(invoice.getPaymentMethod())
                || !CommonInvoiceRouteState.isOwnerPaperInvoice(invoice)
                || PAPER_BANK_ACTOR.equals(invoice.getManualPaidBy())
                || amount <= 0 || positions.isEmpty()
                || positions.stream().anyMatch(item -> !item.isPaid()
                        || !CommonInvoiceManualPaymentWorkflow.PAYMENT_METHOD_OWNER_PAPER_INVOICE.equals(item.getPaymentMethod()))
                || positions.stream().mapToLong(item -> item.getAmountKopecks()).sum() != amount)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Отменить можно только ручную отметку оплаты бумажного счёта без поступления по банку");
        for (var item : positions) {
            orders.reopenPaperInvoiceOrder(item.orderId(), item.getAmountKopecks(), actor, cleanReason);
        }
        reopenInvoice(id, true);
    }

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
        reopenInvoice(id, false);
    }

    /**
     * @param reminder the orders went back to «Напоминание» (a paper invoice paid by mistake): the positions are not
     *                 «Не оплачено» and the invoice keeps reminding when reminders were already sent
     */
    private void reopenInvoice(long id, boolean reminder) {
        // Reversing order rewards flushes and clears the persistence context.
        var invoice = delivery.lockedInvoice(id).orElseThrow();
        for (var item : items.findByInvoiceIdWithOrders(id)) {
            item.setPaid(false);
            item.setPaidAt(null);
            item.setUnpaid(!reminder);
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
        invoice.setStatus(invoice.getSentAt() == null ? CommonInvoiceStatus.READY
                : reminder && invoice.getLastReminderAt() != null ? CommonInvoiceStatus.REMINDER : CommonInvoiceStatus.INVOICED);
        invoice.setPaymentMethod(null);
        invoice.setManualConfirmedAt(null);
        invoice.setManualPaidBy(null);
        invoice.setManualPaymentComment(null);
        invoice.setManualPaymentReceiptUrl(null);
        invoice.setClientReportedAt(null);
        invoice.setLastError(null);
        if (reminder) {
            // The client gets a full new payment term from the reopening: the automatic move to «Не оплачено»
            // counts from now and needs a new notice (the old one belonged to the term before the mistaken payment).
            invoice.setOverdueTermRestartedAt(java.time.LocalDateTime.now());
            invoice.setOverdueNoticeAt(null);
            // A failed «оплата прошла» notice belonged to the undone payment (it is skipped now: the invoice is unpaid).
            invoice.setPaymentSuccessNotificationError(null);
            // Reminders resume one regular interval from now (the manager first corrects the wrong «оплата прошла»),
            // the same rule as after a delivered bill; a paper bill never handed over is not reminded about.
            invoice.setNextReminderAt(invoice.getStatus() == CommonInvoiceStatus.READY
                    || CommonInvoiceRouteState.isOwnerPaperInvoice(invoice) && invoice.getPaperInvoiceIssuedAt() == null
                    ? null : delivery.nextAutomaticPaymentReminderAt(java.time.LocalDateTime.now()));
        }
    }
}
