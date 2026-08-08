package com.hunt.otziv.contractor_payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record ContractorPaymentSystemActivationRequest(
        @NotNull LocalDate attributionStartDate,
        @NotBlank @Size(max = 80) String confirmation,
        @NotBlank @Size(max = 500) String reason,
        @NotNull Long expectedRevision
) {
}
