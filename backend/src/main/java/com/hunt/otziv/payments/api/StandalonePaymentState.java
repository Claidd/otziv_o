package com.hunt.otziv.payments.api;

/** Payment-owned decision used before closing an order through a common invoice. */
public interface StandalonePaymentState {
    /**
     * Must join the caller's transaction after it has locked the canonical Order.
     * Locks current payment rows in Order -> PaymentLink order and returns no JPA entities.
     */
    boolean hasStartedPaymentWithLock(long orderId);
}
