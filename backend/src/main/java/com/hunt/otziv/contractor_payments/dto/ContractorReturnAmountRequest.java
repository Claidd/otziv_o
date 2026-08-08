package com.hunt.otziv.contractor_payments.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record ContractorReturnAmountRequest(
        @Min(0) long returnedTotalKopecks,
        @PastOrPresent LocalDateTime effectiveAt,
        @NotBlank @Size(max = 255) String reason
) {
}
