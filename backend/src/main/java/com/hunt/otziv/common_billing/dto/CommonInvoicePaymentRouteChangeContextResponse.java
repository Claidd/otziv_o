package com.hunt.otziv.common_billing.dto;

public record CommonInvoicePaymentRouteChangeContextResponse(
        String currentRoute,
        CommonInvoicePaymentRouteChangeTarget currentTarget,
        String currentRecipient,
        String status,
        boolean canChange,
        String blockReason,
        String paymentEvidenceToken,
        Long currentPaymentProfileId,
        Long ownerBankTargetPaymentProfileId,
        String ownerBankTargetPaymentProfileName,
        String ownerBankTargetProvider,
        boolean canReissueOwnerBank,
        String ownerBankReissueBlockReason
) {
}
