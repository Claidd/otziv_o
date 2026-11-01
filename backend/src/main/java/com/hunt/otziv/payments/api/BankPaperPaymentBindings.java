package com.hunt.otziv.payments.api;

/** Internal scalar check under the caller's canonical order mutex; no network or mutation. */
public interface BankPaperPaymentBindings {
    boolean hasDocument(long orderId);
}
