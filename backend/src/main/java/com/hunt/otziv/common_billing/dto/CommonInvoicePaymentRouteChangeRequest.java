package com.hunt.otziv.common_billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CommonInvoicePaymentRouteChangeRequest(
        @NotNull CommonInvoicePaymentRouteChangeTarget target,
        boolean confirmedUnpaid,
        @NotBlank String expectedPaymentEvidenceToken,
        Long expectedTargetPaymentProfileId,
        /**
         * Contractor payment profile a manager named for the card number; null lets the system choose;
         * {@link #OWNER_CARD} names the card of the owner.
         */
        Long recipientProfileId
) {
    /** In place of a contractor profile: the manager named the owner's card. No profile has this id. */
    public static final long OWNER_CARD = -1L;

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
