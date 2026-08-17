package com.hunt.otziv.payments.dto;

public record ManualPaymentTaskReturnCommand(
        Long taskId,
        ManualPaymentTaskSourceRef source,
        long amountKopecks,
        String operationKey,
        String actor,
        String reason
) {
}
