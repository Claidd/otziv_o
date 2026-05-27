package com.hunt.otziv.payments.dto;

public record PaymentProfileResponse(
        Long id,
        String code,
        String provider,
        String name,
        String terminalKey,
        String passwordEnvKey,
        boolean enabled,
        boolean defaultProfile,
        boolean testMode,
        boolean hasPassword,
        String paymentPolicy,
        String manualPaymentType,
        String manualPhone,
        String manualRecipientName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel,
        Long manualMonthlySoftLimitKopecks,
        Long manualMonthlyHardLimitKopecks,
        long manualMonthlyUsedKopecks,
        long manualMonthlyConfirmedKopecks,
        long manualMonthlyPendingAmountKopecks,
        long manualMonthlyPendingCount
) {
}
