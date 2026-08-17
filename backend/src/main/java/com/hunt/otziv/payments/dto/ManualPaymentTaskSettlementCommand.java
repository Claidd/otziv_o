package com.hunt.otziv.payments.dto;

public record ManualPaymentTaskSettlementCommand(
        Long taskId,
        long taskGeneration,
        ManualPaymentTaskSourceRef source,
        String selectedRecipientKey,
        long totalReservedAmountKopecks,
        long taskAttributedAmountKopecks,
        String operationKey,
        String actor,
        String reason
) {
}
