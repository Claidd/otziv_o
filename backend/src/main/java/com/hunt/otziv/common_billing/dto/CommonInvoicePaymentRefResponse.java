package com.hunt.otziv.common_billing.dto;

public record CommonInvoicePaymentRefResponse(
        Long id,
        String status,
        String orderId,
        String paymentId,
        Long amountKopecks,
        String terminalLabel,
        String terminalKey,
        String reason
) {
}
