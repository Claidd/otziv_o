package com.hunt.otziv.payments.tochka.dto;

import java.math.BigDecimal;

/**
 * Values already persisted from payment-link creation and therefore safe to
 * use for binding a signed webhook to one exact local payment attempt.
 */
public record TochkaWebhookExpectation(
        String customerCode,
        String merchantId,
        BigDecimal amount,
        String operationId,
        String paymentLinkId,
        String paymentType
) {

    public TochkaWebhookExpectation {
        requireText(customerCode, "customerCode");
        requireText(merchantId, "merchantId");
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        requireText(operationId, "operationId");
        requireText(paymentLinkId, "paymentLinkId");
        requireText(paymentType, "paymentType");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
