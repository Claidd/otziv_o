package com.hunt.otziv.payments.dto;

public record PublicPaymentInitResponse(
        String paymentUrl,
        String paymentId,
        String status
) {
}
