package com.hunt.otziv.common_billing.api;

/**
 * Orders call both operations in their deletion transaction. The first acquires the canonical
 * order/payment/invoice locks without mutation; the second runs only after order authorization.
 * A frozen invoice, payment evidence or changed membership aborts the entire deletion.
 */
public interface CommonInvoiceOrderDeletion {
    Long lockForOrderDeletion(Long orderId);
    void detachForOrderDeletion(Long orderId, Long expectedInvoiceId);
}
