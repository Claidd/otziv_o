package com.hunt.otziv.payments.dto;

public record PaymentRouteChangeContextResponse(
        Long paymentLinkId,
        String currentRoute,
        String currentRecipient,
        String status,
        boolean canChange,
        String blockReason,
        String configuredMode,
        boolean paperInvoiceIssued,
        Long expectedTargetPaymentProfileId
) {
    public PaymentRouteChangeContextResponse(
            Long paymentLinkId,
            String currentRoute,
            String currentRecipient,
            String status,
            boolean canChange,
            String blockReason
    ) {
        this(paymentLinkId, currentRoute, currentRecipient, status, canChange, blockReason,
                "AUTO_ROUTING", false, null);
    }

    public PaymentRouteChangeContextResponse(
            Long paymentLinkId,
            String currentRoute,
            String currentRecipient,
            String status,
            boolean canChange,
            String blockReason,
            String configuredMode,
            boolean paperInvoiceIssued
    ) {
        this(paymentLinkId, currentRoute, currentRecipient, status, canChange, blockReason,
                configuredMode, paperInvoiceIssued, null);
    }
}
