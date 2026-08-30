package com.hunt.otziv.payments.tochka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * Signed claims published by Tochka for the acquiringInternetPayment event.
 * Optional fields differ between card, SBP and Dolyame payments.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TochkaAcquiringInternetPaymentWebhook(
        String customerCode,
        BigDecimal amount,
        String paymentType,
        String operationId,
        String transactionId,
        String purpose,
        String qrcId,
        String merchantId,
        String webhookType,
        String payerName,
        String consumerId,
        String status,
        String paymentLinkId,
        String maskedPan,
        String cardType,
        String tokenCardId
) {
}
