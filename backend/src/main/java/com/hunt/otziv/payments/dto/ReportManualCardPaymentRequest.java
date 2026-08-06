package com.hunt.otziv.payments.dto;

/**
 * A manager report that the client paid outside the T-Bank route.
 * The amount and bank state are resolved exclusively on the server.
 */
public record ReportManualCardPaymentRequest(String reason) {
}
