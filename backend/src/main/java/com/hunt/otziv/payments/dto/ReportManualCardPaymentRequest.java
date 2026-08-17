package com.hunt.otziv.payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;

/**
 * A manager report that the client paid outside the T-Bank route.
 * The amount and bank state are resolved exclusively on the server.
 */
public record ReportManualCardPaymentRequest(
        String reason,
        String receiptUrl,
        ContractorRecipientType recipientType,
        Long recipientProfileId,
        String recipientKey
) {
    public ReportManualCardPaymentRequest(
            String reason, String receiptUrl, ContractorRecipientType recipientType, Long recipientProfileId
    ) {
        this(reason, receiptUrl, recipientType, recipientProfileId, null);
    }

    public ReportManualCardPaymentRequest(String reason) {
        this(reason, null, null, null, null);
    }
}
