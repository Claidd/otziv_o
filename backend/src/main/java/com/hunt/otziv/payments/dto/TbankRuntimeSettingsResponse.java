package com.hunt.otziv.payments.dto;

public record TbankRuntimeSettingsResponse(
        String runtimeMode,
        boolean testMode,
        boolean tbankEnabled,
        boolean paymentLinksEnabled,
        boolean managerUiEnabled,
        boolean applyConfirmedPayments,
        String paymentInstructionSource,
        boolean clientTbankEnabled,
        String paymentPageMode,
        boolean tpayEnabled,
        boolean sberpayEnabled,
        boolean mirpayEnabled
) {
}
