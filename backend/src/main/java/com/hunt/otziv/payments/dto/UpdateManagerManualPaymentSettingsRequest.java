package com.hunt.otziv.payments.dto;

public record UpdateManagerManualPaymentSettingsRequest(
        String manualPaymentType,
        String manualPhone,
        String manualRecipientName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel
) {
}
