package com.hunt.otziv.payments.api;

/** Prevents a second payment route while a specialist transfer is unresolved. */
public interface SpecialistPaymentReviewGate {
    void requireNoPendingReview(Long orderId);

    /** Read-only suppression of payment requests; never authorizes a receipt or route change. */
    boolean hasPendingReview(Long orderId);
}
