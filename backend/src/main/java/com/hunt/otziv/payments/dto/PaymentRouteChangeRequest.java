package com.hunt.otziv.payments.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record PaymentRouteChangeRequest(
        Long expectedPaymentLinkId,
        @NotNull PaymentRouteChangeTarget target,
        @AssertTrue(message = "Подтвердите, что клиент еще не оплатил") boolean confirmedUnpaid,
        Long expectedTargetPaymentProfileId, Boolean paperInvoiceWithdrawn,
        /** Contractor payment profile a manager named for the card number; null lets the system choose. */
        Long recipientProfileId
) {
    public PaymentRouteChangeRequest(Long link, PaymentRouteChangeTarget target, boolean unpaid, Long profile,
            Boolean paperInvoiceWithdrawn) {
        this(link, target, unpaid, profile, paperInvoiceWithdrawn, null);
    }
    public PaymentRouteChangeRequest(Long link, PaymentRouteChangeTarget target, boolean unpaid, Long profile) {
        this(link, target, unpaid, profile, false);
    }

    public PaymentRouteChangeRequest(
            Long expectedPaymentLinkId,
            PaymentRouteChangeTarget target,
            boolean confirmedUnpaid
    ) {
        this(expectedPaymentLinkId, target, confirmedUnpaid, null);
    }
}
