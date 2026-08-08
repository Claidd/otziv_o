package com.hunt.otziv.contractor_payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorRole;
import java.time.LocalDateTime;

public record ContractorPaymentProfileResponse(
        Long id,
        Long userId,
        ContractorRole role,
        long rowVersion,
        boolean enabled,
        boolean liveEnabled,
        String recipientName,
        String paymentPhone,
        String bankName,
        String paymentComment,
        long openingBalanceKopecks,
        LocalDateTime trackingStartedAt,
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
        long exposureOverrunKopecks,
        boolean reportingLive,
        boolean shadowMode,
        boolean liveRouting
) {
}
