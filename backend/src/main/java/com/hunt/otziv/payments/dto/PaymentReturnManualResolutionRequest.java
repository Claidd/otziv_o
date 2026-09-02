package com.hunt.otziv.payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PaymentReturnManualResolutionRequest(
        @NotNull PaymentReturnManualResolutionOutcome outcome,
        @NotBlank @Size(max = 512) String reason,
        @NotBlank @Size(max = 80) String confirmation
) {
}
