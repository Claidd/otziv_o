package com.hunt.otziv.payments.dto;

import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentType;
import java.time.LocalDateTime;

public record ManualPaymentTaskRouteSnapshot(
        Long taskId,
        long taskGeneration,
        ManualPaymentTaskSourceRef source,
        String candidateKey,
        ManualPaymentTaskAccountingTargetKind accountingTargetKind,
        Long accountingTargetProfileId,
        String accountingTargetLabel,
        ManualPaymentType manualPaymentType,
        String manualPhone,
        String bankRecipientName,
        String bankName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel,
        long reservedAmountKopecks,
        LocalDateTime targetOverrunAcknowledgedAt,
        String targetOverrunAcknowledgedBy
) {
    /** Legacy snapshot shape kept for tests and mixed-version callers. */
    public ManualPaymentTaskRouteSnapshot(
            Long taskId,
            long taskGeneration,
            ManualPaymentTaskSourceRef source,
            String candidateKey,
            ManualPaymentTaskAccountingTargetKind accountingTargetKind,
            Long accountingTargetProfileId,
            String accountingTargetLabel,
            ManualPaymentType manualPaymentType,
            String manualPhone,
            String bankRecipientName,
            String manualPaymentUrl,
            String manualPaymentButtonLabel,
            long reservedAmountKopecks,
            LocalDateTime targetOverrunAcknowledgedAt,
            String targetOverrunAcknowledgedBy
    ) {
        this(
                taskId,
                taskGeneration,
                source,
                candidateKey,
                accountingTargetKind,
                accountingTargetProfileId,
                accountingTargetLabel,
                manualPaymentType,
                manualPhone,
                bankRecipientName,
                null,
                manualPaymentUrl,
                manualPaymentButtonLabel,
                reservedAmountKopecks,
                targetOverrunAcknowledgedAt,
                targetOverrunAcknowledgedBy
        );
    }
}
