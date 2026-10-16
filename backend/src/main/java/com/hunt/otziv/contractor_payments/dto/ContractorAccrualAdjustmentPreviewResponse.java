package com.hunt.otziv.contractor_payments.dto;

public record ContractorAccrualAdjustmentPreviewResponse(
        long workMonthKopecks,
        long bonusMonthKopecks,
        long deductionMonthKopecks,
        long accruedMonthBeforeKopecks,
        long accruedMonthAfterKopecks,
        long freeForNewPaymentsBeforeKopecks,
        long freeForNewPaymentsAfterKopecks,
        long paidTotalKopecks,
        long outstandingKopecks,
        long manualTaskCommitmentKopecks
) {
}
