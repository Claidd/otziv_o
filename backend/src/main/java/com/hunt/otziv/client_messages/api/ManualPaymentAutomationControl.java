package com.hunt.otziv.client_messages.api;

import java.util.Optional;

/** Coordinates payment mutations with durable client payment instructions. */
public interface ManualPaymentAutomationControl {
    /** Queue the invoice for the amount currently due after a verified bank closure. */
    void scheduleRepricedBadReviewInvoice(long orderId);

    int closePaymentAutomationForManualSettlement(Long orderId, String reason);

    /** Caller holds the order lock; message row locks remain until its transaction completes. */
    void assertPaymentRouteReplaceable(Long orderId);

    /** Read-only preview; the mutation must repeat the check under locks. */
    Optional<String> paymentRouteReplacementBlockReason(Long orderId);
}
