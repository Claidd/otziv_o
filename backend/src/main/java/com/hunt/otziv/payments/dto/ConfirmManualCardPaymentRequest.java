package com.hunt.otziv.payments.dto;

/**
 * Explicit operator assertions for a payment received outside T-Bank.
 * Both booleans are intentionally required: a message or screenshot from the
 * client is not sufficient without checking the recipient's statement.
 */
public record ConfirmManualCardPaymentRequest(
        Boolean recipientStatementChecked,
        Boolean paymentReceived,
        Long receivedAmountKopecks,
        String note,
        String receiptUrl
) {
}
