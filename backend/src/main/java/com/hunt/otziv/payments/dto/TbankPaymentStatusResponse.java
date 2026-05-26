package com.hunt.otziv.payments.dto;

public record TbankPaymentStatusResponse(
        boolean enabled,
        boolean paymentLinksEnabled,
        boolean managerUiEnabled,
        boolean applyConfirmedPayments,
        boolean hasCredentials,
        boolean testMode,
        String runtimeMode,
        String baseUrl,
        String publicBaseUrl,
        String notificationUrl,
        String successUrl,
        String failUrl
) {
}
