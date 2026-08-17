package com.hunt.otziv.payments.dto;

public record UpdateManualPaymentTaskRequest(
        String manualPaymentType,
        String manualPhone,
        String manualRecipientName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel,
        Long targetAmountKopecks,
        String comment,
        Boolean manualPaymentUrlReplacementConfirmed,
        String accountingTargetKind,
        Long accountingTargetProfileId,
        Boolean accountingTargetOverrunAcknowledged,
        Long expectedGeneration
) {
    /** Legacy request shape kept for Java callers and mixed-version clients. */
    public UpdateManualPaymentTaskRequest(
            String manualPaymentType,
            String manualPhone,
            String manualRecipientName,
            String manualPaymentUrl,
            String manualPaymentButtonLabel,
            Long targetAmountKopecks,
            String comment
    ) {
        this(
                manualPaymentType,
                manualPhone,
                manualRecipientName,
                manualPaymentUrl,
                manualPaymentButtonLabel,
                targetAmountKopecks,
                comment,
                false,
                null,
                null,
                false,
                null
        );
    }
}
