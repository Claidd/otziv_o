package com.hunt.otziv.contractor_payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContractorAccrualAdjustmentReversalRequest(
        @NotBlank @Size(max = 255) String reason,
        @NotBlank @Size(max = 120) String idempotencyKey
) {
}
