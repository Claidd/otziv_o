package com.hunt.otziv.payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;

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
        String receiptUrl,
        ContractorRecipientType recipientType,
        Long recipientProfileId,
        String recipientKey
) {
    public ConfirmManualCardPaymentRequest(
            Boolean recipientStatementChecked, Boolean paymentReceived, Long receivedAmountKopecks,
            String note, String receiptUrl, ContractorRecipientType recipientType, Long recipientProfileId
    ) {
        this(recipientStatementChecked, paymentReceived, receivedAmountKopecks, note, receiptUrl,
                recipientType, recipientProfileId, null);
    }

    public ConfirmManualCardPaymentRequest(
            Boolean recipientStatementChecked,
            Boolean paymentReceived,
            Long receivedAmountKopecks,
            String note,
            String receiptUrl
    ) {
        this(recipientStatementChecked, paymentReceived, receivedAmountKopecks, note, receiptUrl, null, null, null);
    }
}
