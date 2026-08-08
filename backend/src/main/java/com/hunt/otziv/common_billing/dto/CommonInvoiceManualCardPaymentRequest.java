package com.hunt.otziv.common_billing.dto;

/** A manager report that the remaining common-invoice amount was paid directly to a card. */
public record CommonInvoiceManualCardPaymentRequest(String reason) {
}
