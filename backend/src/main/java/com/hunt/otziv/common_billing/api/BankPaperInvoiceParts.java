package com.hunt.otziv.common_billing.api;

import java.security.Principal;
import java.util.List;

/** Scalar boundary for immutable subsets; all mutations retain canonical order -> invoice locks. */
public interface BankPaperInvoiceParts {
    record Position(long orderId,long companyId,String companyTitle,long amountKopecks,boolean ready,boolean paid,String documentId) {}
    List<Position> positions(long invoiceId);
    List<Long> historyCompanyIds(long invoiceId);
    List<Long> partIds(long invoiceId);
    List<Long> historyInvoiceIds(long invoiceId);
    java.time.LocalDateTime nextReminder(long partId);
    boolean remindersAllowed(long partId);
    Long parentForOrder(long orderId);
    long create(long invoiceId,List<Long> orders,String requestKey);
    long parent(long partId);
    List<Position> members(long partId);
    BankPaperInvoices.Snapshot read(long partId);
    BankPaperInvoices.Snapshot prepare(long partId);
    void configure(long invoiceId,Principal actor);
    String composition(long partId);
    void bind(long partId,String document);
    void lock(long partId);
    void delivered(long partId,String document);
    void settle(long partId,long amount,String payment);
    void requireUnpaid(long partId);
    void hold(long partId,boolean hold);
    void review(long partId);
    void cancelled(long partId,String document);
    void resumeOrdinaryInvoice(long partId);
    BankPaperInvoices.StaffContext staffContext(long partId);
}
