package com.hunt.otziv.contractor_payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorAccrualAdjustmentKind;
import java.time.LocalDate;

/** Employee-facing correction without internal document IDs or staff audit fields. */
public record ContractorAccrualAdjustmentSelfResponse(
        Long id,
        Long profileId,
        ContractorAccrualAdjustmentKind kind,
        long signedAmountKopecks,
        LocalDate effectiveOn,
        String referenceMonth,
        String reason,
        Long reversalOfId
) {
}
