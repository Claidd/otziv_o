package com.hunt.otziv.payments.dto;

/**
 * Explicit operator assertion used to retire a manual payment instruction
 * after checking the recipient account statement.
 */
public record CloseManualPaymentUnpaidRequest(
        Boolean recipientStatementChecked,
        Boolean paymentAbsent,
        String note
) {
}
