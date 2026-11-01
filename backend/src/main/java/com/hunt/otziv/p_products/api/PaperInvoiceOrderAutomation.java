package com.hunt.otziv.p_products.api;

/** Caller owns the order mutex and has explicitly selected a new payment route. */
public interface PaperInvoiceOrderAutomation {
    void resumeAfterRouteChange(long orderId);
}
