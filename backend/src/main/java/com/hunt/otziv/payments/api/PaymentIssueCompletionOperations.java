package com.hunt.otziv.payments.api;

/** Closes a verified common-invoice order's fail-closed staff warning in the settlement transaction. */
public interface PaymentIssueCompletionOperations {
    int completePaidCommonInvoiceOrderIssueInCurrentTransaction(Long invoiceId, Long orderId);
}
