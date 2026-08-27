package com.hunt.otziv.payments.dto;

import com.hunt.otziv.payments.model.ManualPaymentType;

public record CreateManualPaymentTaskRequest(
        Long managerId,
        String manualPaymentType,
        String manualPhone,
        String manualRecipientName,
        String manualBankName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel,
        Long targetAmountKopecks,
        String comment,
        String accountingTargetKind,
        Long accountingTargetProfileId,
        Boolean accountingTargetOverrunAcknowledged,
        String operationKey
) {
    /** Legacy request shape kept for Java callers and mixed-version clients. */
    public CreateManualPaymentTaskRequest(
            Long managerId,
            String manualPaymentType,
            String manualPhone,
            String manualRecipientName,
            String manualPaymentUrl,
            String manualPaymentButtonLabel,
            Long targetAmountKopecks,
            String comment,
            String accountingTargetKind,
            Long accountingTargetProfileId,
            Boolean accountingTargetOverrunAcknowledged,
            String operationKey
    ) {
        this(
                managerId,
                manualPaymentType,
                manualPhone,
                manualRecipientName,
                null,
                manualPaymentUrl,
                manualPaymentButtonLabel,
                targetAmountKopecks,
                comment,
                accountingTargetKind,
                accountingTargetProfileId,
                accountingTargetOverrunAcknowledged,
                operationKey
        );
    }
}
