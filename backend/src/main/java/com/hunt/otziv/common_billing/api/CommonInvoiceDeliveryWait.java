package com.hunt.otziv.common_billing.api;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Billing-owned view of a common invoice's queued client message that is held by the
 * autoresponder's rules (business window, shared daily limit, sending interval, pause or
 * live switch). Such a message was never sent and will go out on its own, so it is the
 * autoresponder's work rather than an unconfirmed delivery.
 */
public interface CommonInvoiceDeliveryWait {

    /** Queue codes of a send the autoresponder postponed before dispatch. */
    Set<String> AUTORESPONDER_HOLD_CODES = Set.of(
            "outside_business_window", "daily_limit", "rate_limited", "automation_paused", "live_disabled");

    /**
     * @param reasonCode the hold code, or empty while the message is queued and not yet tried
     * @param queuedAt   when the message entered the queue
     */
    record Wait(String reasonCode, LocalDateTime queuedAt) {}

    /** Present only while the operation is queued or held by the autoresponder; never for a send in doubt. */
    Optional<Wait> autoresponderWait(String operationId);
}
