package com.hunt.otziv.common_billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CommonManualPaymentTaskReturnRequest(
        @Positive Long attributionId,
        @NotBlank @Size(max = 160) String evidenceReference,
        @PositiveOrZero long cumulativeReturnedKopecks,
        @NotBlank @Size(max = 500) String reason
) {
}
