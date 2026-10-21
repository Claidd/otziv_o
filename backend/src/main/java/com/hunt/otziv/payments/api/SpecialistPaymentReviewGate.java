package com.hunt.otziv.payments.api;

/** Prevents a second payment route while a specialist transfer is unresolved. */
public interface SpecialistPaymentReviewGate {
    void requireNoPendingReview(Long orderId);
}
