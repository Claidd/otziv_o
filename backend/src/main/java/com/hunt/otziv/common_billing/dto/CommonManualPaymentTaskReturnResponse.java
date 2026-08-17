package com.hunt.otziv.common_billing.dto;

public record CommonManualPaymentTaskReturnResponse(
        Long invoiceId,
        Long attributionId,
        Long taskId,
        long cumulativeReturnedKopecks,
        long appliedDeltaKopecks,
        boolean replay
) {
}
