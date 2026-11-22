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
     * Holds set by the autoresponder's schedule (business window, shared daily limit, sending
     * interval). They lift on their own, so queue health counts such a message as late only once
     * its re-check is overdue. Pause and the live switch are left out: nothing lifts them without
     * a person, so a message waiting behind them is still reported as delayed.
     */
    Set<String> SCHEDULE_HOLD_CODES = Set.of("outside_business_window", "daily_limit", "rate_limited");

    /**
     * @param reasonCode the hold code, or empty while the message is queued and not yet tried
     * @param queuedAt   when the message entered the queue
     */
    record Wait(String reasonCode, LocalDateTime queuedAt) {}

    /** Present only while the operation is queued or held by the autoresponder; never for a send in doubt. */
    Optional<Wait> autoresponderWait(String operationId);
}
