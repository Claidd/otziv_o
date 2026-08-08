package com.hunt.otziv.contractor_payments.dto;

import java.time.LocalDateTime;

public record ContractorPaymentProfileAdjustmentResponse(
        Long id,
        Long profileId,
        long oldBalanceKopecks,
        long newBalanceKopecks,
        long deltaKopecks,
        String reason,
        String changedBy,
        LocalDateTime effectiveAt,
        LocalDateTime createdAt
) {
}
