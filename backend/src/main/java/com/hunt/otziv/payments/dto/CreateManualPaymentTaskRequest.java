package com.hunt.otziv.payments.dto;

import com.hunt.otziv.payments.model.ManualPaymentType;

public record CreateManualPaymentTaskRequest(
        Long managerId,
        String manualPaymentType,
        String manualPhone,
        String manualRecipientName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel,
        Long targetAmountKopecks,
        String comment
) {
}
