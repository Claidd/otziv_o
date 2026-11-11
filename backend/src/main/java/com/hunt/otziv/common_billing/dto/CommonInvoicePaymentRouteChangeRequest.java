package com.hunt.otziv.common_billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CommonInvoicePaymentRouteChangeRequest(
        @NotNull CommonInvoicePaymentRouteChangeTarget target,
        boolean confirmedUnpaid,
        @NotBlank String expectedPaymentEvidenceToken,
        Long expectedTargetPaymentProfileId,
        /** Contractor payment profile a manager named for the card number; null lets the system choose. */
        Long recipientProfileId
) {
    public CommonInvoicePaymentRouteChangeRequest(
            CommonInvoicePaymentRouteChangeTarget target,
            boolean confirmedUnpaid,
            String expectedPaymentEvidenceToken,
            Long expectedTargetPaymentProfileId
    ) {
        this(target, confirmedUnpaid, expectedPaymentEvidenceToken, expectedTargetPaymentProfileId, null);
    }

    public CommonInvoicePaymentRouteChangeRequest(
            CommonInvoicePaymentRouteChangeTarget target,
            boolean confirmedUnpaid,
            String expectedPaymentEvidenceToken
    ) {
        this(target, confirmedUnpaid, expectedPaymentEvidenceToken, null);
    }
}
