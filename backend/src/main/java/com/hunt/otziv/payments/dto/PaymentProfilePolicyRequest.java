package com.hunt.otziv.payments.dto;

import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentPolicy;

public record PaymentProfilePolicyRequest(
        Long profileId,
        String paymentPolicy,
        String manualPaymentType,
        String manualPhone,
        String manualRecipientName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel,
        String manualComment,
        Long manualMonthlySoftLimitKopecks,
        Long manualMonthlyHardLimitKopecks,
        Boolean manualPaymentUrlReplacementConfirmed
) {
    /**
     * Source-compatible constructor for server-side callers and tests using the
     * legacy request contract. JSON clients may omit the new marker as well.
     */
    public PaymentProfilePolicyRequest(
            Long profileId,
            String paymentPolicy,
            String manualPaymentType,
            String manualPhone,
            String manualRecipientName,
            String manualPaymentUrl,
            String manualPaymentButtonLabel,
            String manualComment,
            Long manualMonthlySoftLimitKopecks,
            Long manualMonthlyHardLimitKopecks
    ) {
        this(
                profileId,
                paymentPolicy,
                manualPaymentType,
                manualPhone,
                manualRecipientName,
                manualPaymentUrl,
                manualPaymentButtonLabel,
                manualComment,
                manualMonthlySoftLimitKopecks,
                manualMonthlyHardLimitKopecks,
                false
        );
    }
}
