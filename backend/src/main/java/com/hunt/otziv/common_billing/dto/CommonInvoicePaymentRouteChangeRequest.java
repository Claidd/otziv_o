package com.hunt.otziv.common_billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CommonInvoicePaymentRouteChangeRequest(
        @NotNull CommonInvoicePaymentRouteChangeTarget target,
        boolean confirmedUnpaid,
        @NotBlank String expectedPaymentEvidenceToken,
        Long expectedTargetPaymentProfileId
) {
    public CommonInvoicePaymentRouteChangeRequest(
            CommonInvoicePaymentRouteChangeTarget target,
            boolean confirmedUnpaid,
            String expectedPaymentEvidenceToken
    ) {
        this(target, confirmedUnpaid, expectedPaymentEvidenceToken, null);
    }
}
