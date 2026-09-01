package com.hunt.otziv.payments.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record PaymentRouteChangeRequest(
        Long expectedPaymentLinkId,
        @NotNull PaymentRouteChangeTarget target,
        @AssertTrue(message = "Подтвердите, что клиент еще не оплатил") boolean confirmedUnpaid,
        Long expectedTargetPaymentProfileId
) {
    public PaymentRouteChangeRequest(
            Long expectedPaymentLinkId,
            PaymentRouteChangeTarget target,
            boolean confirmedUnpaid
    ) {
        this(expectedPaymentLinkId, target, confirmedUnpaid, null);
    }
}
