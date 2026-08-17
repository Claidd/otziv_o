package com.hunt.otziv.payments.dto;

public record ManualPaymentTaskCorrectionCommand(
        Long taskId,
        ManualPaymentTaskSourceRef source,
        long reservedDeltaKopecks,
        long confirmedDeltaKopecks,
        Long correctionOfEntryId,
        String operationKey,
        String actor,
        String reason
) {
}
