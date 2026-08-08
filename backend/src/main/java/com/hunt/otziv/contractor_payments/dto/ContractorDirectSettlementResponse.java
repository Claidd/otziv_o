package com.hunt.otziv.contractor_payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorDirectSettlementType;
import java.time.LocalDateTime;

public record ContractorDirectSettlementResponse(
        Long id,
        Long profileId,
        Long userId,
        ContractorDirectSettlementType type,
        ContractorAllocationMode mode,
        boolean simulated,
        long amountKopecks,
        LocalDateTime effectiveAt,
        String reason,
        String evidenceReference,
        String idempotencyKey,
        String actor,
        LocalDateTime createdAt,
        Long originalSettlementId,
        Long allocationId
) {
}
