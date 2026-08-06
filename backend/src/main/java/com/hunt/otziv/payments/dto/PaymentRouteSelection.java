package com.hunt.otziv.payments.dto;

public record PaymentRouteSelection(
        String routeType,
        Long paymentProfileId,
        String paymentProfileCode,
        String paymentProfileName,
        String paymentProfileTerminalKey,
        String manualSource,
        Long manualTaskId,
        String manualPaymentType,
        String manualPhone,
        String manualRecipientName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel,
        String manualComment,
        String instructionText
) {
}
