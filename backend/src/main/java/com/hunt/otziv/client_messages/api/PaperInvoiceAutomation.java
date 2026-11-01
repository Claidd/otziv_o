package com.hunt.otziv.client_messages.api;

/** Reconciles the current status cycle in the caller's transaction; never contacts a provider. */
public interface PaperInvoiceAutomation {
    void synchronizeOrder(long orderId);
    void scheduleOrdinaryInvoice(long orderId);
}
