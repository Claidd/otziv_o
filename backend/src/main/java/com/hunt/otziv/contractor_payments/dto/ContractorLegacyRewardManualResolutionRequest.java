package com.hunt.otziv.contractor_payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ContractorLegacyRewardManualResolutionRequest(
        @NotBlank String snapshotHash,
        @NotBlank String groupHash,
        @NotNull LocalDate completedOn,
        @NotBlank String evidenceReference,
        @NotBlank String reason,
        @NotBlank String confirmation
) {
}
