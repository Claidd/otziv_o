package com.hunt.otziv.common_billing.dto;

import com.hunt.otziv.payments.dto.PaymentRouteChangeTarget;

public record CommonInvoicePaymentRouteChangeContextResponse(
        String currentRoute,
        PaymentRouteChangeTarget currentTarget,
        String currentRecipient,
        String status,
        boolean canChange,
        String blockReason,
        String paymentEvidenceToken
) {
}
