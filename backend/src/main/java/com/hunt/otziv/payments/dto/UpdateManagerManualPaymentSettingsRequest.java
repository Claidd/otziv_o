package com.hunt.otziv.payments.dto;

import com.hunt.otziv.payments.model.ManualPaymentType;

public record UpdateManagerManualPaymentSettingsRequest(
        String manualPaymentType,
        String manualPhone,
        String manualRecipientName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel
) {
}
