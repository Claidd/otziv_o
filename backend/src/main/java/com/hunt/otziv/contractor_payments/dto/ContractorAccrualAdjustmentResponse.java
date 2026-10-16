package com.hunt.otziv.contractor_payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorAccrualAdjustmentKind;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ContractorAccrualAdjustmentResponse(
        Long id,
        Long profileId,
        ContractorAccrualAdjustmentKind kind,
        long signedAmountKopecks,
        LocalDate effectiveOn,
        String referenceMonth,
        String reason,
        String evidenceReference,
        String createdBy,
        LocalDateTime createdAt,
        Long reversalOfId
) {
}
