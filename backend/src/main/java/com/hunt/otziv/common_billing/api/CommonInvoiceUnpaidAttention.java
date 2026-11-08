package com.hunt.otziv.common_billing.api;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Billing-owned verdict on a common invoice in "Не оплачено". Moving there starts the
 * autoresponder's bad-review cycle: bad reviews on schedule, and after each completed one an
 * invoice with the new amount. While that runs the invoice is the autoresponder's work and does
 * not need the manager. A reason is returned only when the cycle stalls or has ended and the
 * manager has to decide.
 */
public interface CommonInvoiceUnpaidAttention {

    /** Empty while the bad-review cycle runs on its own; otherwise the reason for the manager. */
    Optional<String> attention(Long invoiceId, LocalDateTime now);
}
