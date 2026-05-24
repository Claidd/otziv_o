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
        boolean hasPassword
) {
}
