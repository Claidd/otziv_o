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
        Long manualMonthlyHardLimitKopecks
) {
}
