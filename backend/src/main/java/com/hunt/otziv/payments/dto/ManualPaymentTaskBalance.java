package com.hunt.otziv.payments.dto;

public record ManualPaymentTaskBalance(
        long pendingAmountKopecks,
        /** Confirmed-to-task amount net of returns and corrections. */
        long netConfirmedAmountKopecks,
        /** Money occupying the task target: pending + net confirmed. */
        long occupiedAmountKopecks,
        long redirectedAmountKopecks,
        long releasedAmountKopecks,
        long returnedAmountKopecks,
        long unverifiedConfirmedAmountKopecks,
        long pendingCount,
        long needsReconciliationCount,
        boolean needsReconciliation
) {
    public static ManualPaymentTaskBalance empty(boolean needsReconciliation) {
        return new ManualPaymentTaskBalance(0, 0, 0, 0, 0, 0, 0, 0, 0, needsReconciliation);
    }
}
