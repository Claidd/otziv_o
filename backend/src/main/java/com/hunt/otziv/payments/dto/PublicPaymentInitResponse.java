package com.hunt.otziv.payments.dto;

public record PublicPaymentInitResponse(
        String paymentUrl,
        String paymentId,
        String status,
        String method,
        String qrPayload,
        String qrImage
) {
    public PublicPaymentInitResponse(String paymentUrl, String paymentId, String status) {
        this(paymentUrl, paymentId, status, "BANK_FORM", null, null);
    }
}
