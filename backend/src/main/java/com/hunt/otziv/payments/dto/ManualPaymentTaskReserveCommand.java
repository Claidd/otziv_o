package com.hunt.otziv.payments.dto;

public record ManualPaymentTaskReserveCommand(
        Long managerId,
        Long paymentProfileId,
        ManualPaymentTaskSourceRef source,
        long amountKopecks,
        String operationKey,
        String actor
) {
}
