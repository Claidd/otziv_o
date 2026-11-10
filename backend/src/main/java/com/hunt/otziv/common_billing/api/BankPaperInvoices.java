package com.hunt.otziv.common_billing.api;

import com.hunt.otziv.payments.model.InvoicePaymentMode;

import java.security.Principal;

/** Billing-owned seam. Read/configure retain current-user scope; prepare/bind join caller locks.
 * settle is internal-only after a verified, deduplicated receipt; delivered requires two provider receipts. */
public interface BankPaperInvoices {
    record Snapshot(long companyId,long amountKopecks) {}
    Snapshot read(long invoiceId);
    Snapshot prepare(long invoiceId);
    void configure(long invoiceId,Principal actor);
    void waitForBankBeforeIssue(long invoiceId, org.springframework.security.core.Authentication actor, String documentRequest);

    /** Creates the requested local document and outbox entry in the verified mode-change transaction. */
    interface DocumentPreparation {
        void prepareAfterBankClosure(long invoiceId, String request, org.springframework.security.core.Authentication actor);
    }
    String deliveryClient(long invoiceId,String companyClient);
    String composition(long invoiceId);
    void bindDocument(long invoiceId,String documentId);
    void settle(long invoiceId,long amountKopecks,String paymentId);
    void delivered(long invoiceId,String documentId);
    void lockForDelivery(long invoiceId);
    void holdAutomation(long invoiceId,boolean hold);
    void cancelled(long invoiceId,String documentId);
    void requireUnpaidForCancellation(long invoiceId);
    void holdForPaymentReview(long invoiceId);
    void resumeOrdinaryInvoice(long invoiceId);
    void resumeOrdinaryInvoice(long invoiceId, InvoicePaymentMode mode);
    record StaffContext(java.util.List<Long> managerIds,java.util.List<Long> orderIds,String title) {}
    StaffContext staffContext(long invoiceId);
}
