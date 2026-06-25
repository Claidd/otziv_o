package com.hunt.otziv.payments.dto;

public record UpdateManualPaymentTaskRequest(
        String manualPaymentType,
        String manualPhone,
        String manualRecipientName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel,
        Long targetAmountKopecks,
        String comment
) {
}
