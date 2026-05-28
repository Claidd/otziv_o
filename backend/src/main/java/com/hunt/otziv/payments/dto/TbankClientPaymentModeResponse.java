package com.hunt.otziv.payments.dto;

public record TbankClientPaymentModeResponse(
        boolean enabled,
        String paymentInstructionSource
) {
}
