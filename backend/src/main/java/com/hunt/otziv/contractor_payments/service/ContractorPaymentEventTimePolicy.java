package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import java.time.LocalDateTime;

/** Keeps contractor accounting events on the real business timeline. */
final class ContractorPaymentEventTimePolicy {

    private ContractorPaymentEventTimePolicy() {
    }

    static LocalDateTime paymentLinkClosedAt(PaymentLink link, LocalDateTime observedAt) {
        if (observedAt == null) {
            throw new IllegalArgumentException("Payment-link observation time is required");
        }
        if (link == null) {
            return observedAt;
        }
        if (link.getStatus() == PaymentLinkStatus.EXPIRED) {
            LocalDateTime expiresAt = link.getExpiresAt();
            if (expiresAt != null && !expiresAt.isAfter(observedAt)) {
                return expiresAt;
            }
            // EXPIRED is also used when an unstarted link is retired early.
            // Its original deadline is not the time when the reservation was released.
            return observedAt;
        }
        LocalDateTime updatedAt = link.getUpdatedAt();
        return updatedAt == null || updatedAt.isAfter(observedAt) ? observedAt : updatedAt;
    }
}
