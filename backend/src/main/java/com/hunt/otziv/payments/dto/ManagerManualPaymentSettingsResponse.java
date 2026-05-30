package com.hunt.otziv.payments.dto;

import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentPolicy;

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
