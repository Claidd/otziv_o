package com.hunt.otziv.contractor_payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorRole;

/**
 * Admin/owner financial overview for the score page.
 *
 * <p>Intentionally excludes payment requisites; the score page needs amounts,
 * not card/phone snapshots.</p>
 */
public record ContractorPaymentAdminSummaryResponse(
        Long profileId,
        Long userId,
        String fio,
        ContractorRole role,
        boolean profileEnabled,
        boolean liveEnabled,
        long accruedMonthKopecks,
        long accruedTotalKopecks,
        long reservedKopecks,
        long pendingKopecks,
        long paidMonthKopecks,
        long paidTotalKopecks,
        long actualTransferCount,
        long actualTransferAmountKopecks,
        long availableKopecks,
        boolean reportingLive,
        boolean currentMonthCoverageComplete
) {
}
