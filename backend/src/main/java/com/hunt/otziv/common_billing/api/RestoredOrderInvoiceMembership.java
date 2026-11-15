package com.hunt.otziv.common_billing.api;

/** Rechecks the active company billing link in the transaction restoring an individual order. */
public interface RestoredOrderInvoiceMembership {
    /** True when the order is already attached or was added to its current common invoice. */
    boolean attachOrderByIdIfNeeded(Long orderId);
}
