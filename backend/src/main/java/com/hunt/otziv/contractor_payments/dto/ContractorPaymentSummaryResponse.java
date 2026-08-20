package com.hunt.otziv.contractor_payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorRole;
import java.time.LocalDateTime;

/** Financial visibility for one of the authenticated contractor's own roles. */
public record ContractorPaymentSummaryResponse(
        Long profileId,
        Long userId,
        ContractorRole role,
        boolean profileEnabled,
        boolean liveEnabled,
        String recipientName,
        String paymentPhone,
        String bankName,
        String paymentComment,
        long accruedMonthKopecks,
        long accruedTotalKopecks,
        long reservedKopecks,
        long clientReportedKopecks,
        long partiallyConfirmedOutstandingKopecks,
        long grossConfirmedMonthKopecks,
        long grossConfirmedTotalKopecks,
        long returnedMonthKopecks,
        long returnedTotalKopecks,
        long closedWithoutPaymentMonthKopecks,
        long closedWithoutPaymentTotalKopecks,
        long netReceivedMonthKopecks,
        long netReceivedTotalKopecks,
        long availableKopecks,
        long creditKopecks,
        long exposureOverrunKopecks,
        boolean reportingLive,
        boolean shadowMode,
        boolean liveRouting,
        LocalDateTime trackingStartedAt,
        boolean currentMonthCoverageComplete
) {
}
