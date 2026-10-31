package com.hunt.otziv.common_billing.api;

import java.security.Principal;

/** Billing-owned seam. Read/configure retain current-user scope; prepare/bind join caller locks.
 * settle is internal-only after a verified, deduplicated receipt; delivered requires two provider receipts. */
public interface BankPaperInvoices {
    record Snapshot(long companyId,long amountKopecks) {}
    Snapshot read(long invoiceId);
    Snapshot prepare(long invoiceId);
    void configure(long invoiceId,Principal actor);
    String deliveryClient(long invoiceId,String companyClient);
    String composition(long invoiceId);
    void bindDocument(long invoiceId,String documentId);
    void settle(long invoiceId,long amountKopecks,String paymentId);
    void delivered(long invoiceId,String documentId);
    void lockForDelivery(long invoiceId);
    record StaffContext(java.util.List<Long> managerIds,java.util.List<Long> orderIds,String title) {}
    StaffContext staffContext(long invoiceId);
}
