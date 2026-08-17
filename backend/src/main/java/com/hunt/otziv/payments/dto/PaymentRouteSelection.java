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
        String instructionText,
        String manualTaskSourceGeneration,
        Long manualTaskGeneration
) {
    public PaymentRouteSelection(
            String routeType, Long paymentProfileId, String paymentProfileCode,
            String paymentProfileName, String paymentProfileTerminalKey, String manualSource,
            Long manualTaskId, String manualPaymentType, String manualPhone,
            String manualRecipientName, String manualPaymentUrl, String manualPaymentButtonLabel,
            String manualComment, String instructionText
    ) {
        this(routeType, paymentProfileId, paymentProfileCode, paymentProfileName,
                paymentProfileTerminalKey, manualSource, manualTaskId, manualPaymentType,
                manualPhone, manualRecipientName, manualPaymentUrl, manualPaymentButtonLabel,
                manualComment, instructionText, null, null);
    }
}
