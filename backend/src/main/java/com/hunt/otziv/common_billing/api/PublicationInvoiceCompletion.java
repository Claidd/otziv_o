package com.hunt.otziv.common_billing.api;

/** Idempotent publication follow-up; must run after releasing the publishing Order transaction. */
public interface PublicationInvoiceCompletion {
    /** False retains the durable work while current members/recovery are not ready. */
    boolean finalizePublishedInvoiceForOrder(long orderId);
}
