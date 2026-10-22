package com.hunt.otziv.contractor_payments.api;

/** Locks attribution evidence before a payment-link recipient intent can be cleared. */
public interface PaymentLinkAttributionGuard {
    void requireNoFinalAttributionForPaymentLink(Long paymentLinkId);
}
