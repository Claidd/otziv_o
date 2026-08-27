package com.hunt.otziv.payments.dto;

public record PaymentRouteChangeResponse(
        Long previousPaymentLinkId,
        Long paymentLinkId,
        PaymentRouteChangeTarget target,
        boolean clientNotificationScheduled,
        ManagerPaymentLinkResponse payment
) {
}
