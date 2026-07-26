package com.hunt.otziv.common_billing.dto;

public record ManualPaymentConfirmationRequest(
        String comment,
        String receiptUrl
) {
}
