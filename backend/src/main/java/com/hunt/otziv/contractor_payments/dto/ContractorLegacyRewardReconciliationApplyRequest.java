package com.hunt.otziv.contractor_payments.dto;

import jakarta.validation.constraints.NotBlank;

public record ContractorLegacyRewardReconciliationApplyRequest(
        @NotBlank String snapshotHash,
        @NotBlank String reason,
        @NotBlank String confirmation
) {
}
