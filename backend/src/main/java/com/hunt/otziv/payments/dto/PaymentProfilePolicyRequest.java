package com.hunt.otziv.payments.dto;

public record PaymentProfilePolicyRequest(
        Long profileId,
        String paymentPolicy,
        String manualPaymentType,
        String manualPhone,
        String manualRecipientName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel,
        Long manualMonthlySoftLimitKopecks,
        Long manualMonthlyHardLimitKopecks
) {
}
