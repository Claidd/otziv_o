package com.hunt.otziv.common_billing.api;

/** Scalar guard; caller owns the canonical order mutex and authorizes the mutation. */
public interface BankPaperInvoiceBindings {
    boolean hasDocument(long orderId);
}
