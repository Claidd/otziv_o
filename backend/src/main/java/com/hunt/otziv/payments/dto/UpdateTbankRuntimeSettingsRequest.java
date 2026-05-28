package com.hunt.otziv.payments.dto;

public record UpdateTbankRuntimeSettingsRequest(
        String runtimeMode,
        Boolean tbankEnabled,
        Boolean paymentLinksEnabled,
        Boolean managerUiEnabled,
        Boolean applyConfirmedPayments,
        String paymentInstructionSource,
        String paymentPageMode,
        Boolean tpayEnabled,
        Boolean sberpayEnabled,
        Boolean mirpayEnabled
) {
}
