package com.hunt.otziv.payments.dto;

public record ManagerManualPaymentSettingsResponse(
        Long profileId,
        String profileName,
        String paymentPolicy,
        boolean manualPaymentEnabled,
        String manualPaymentType,
        String manualPhone,
        String manualRecipientName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel
) {
}
