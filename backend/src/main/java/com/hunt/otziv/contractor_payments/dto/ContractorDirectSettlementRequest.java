package com.hunt.otziv.contractor_payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentAmountLimits;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record ContractorDirectSettlementRequest(
        @NotNull ContractorAllocationMode expectedMode,
        @Min(1) @Max(ContractorPaymentAmountLimits.MAX_AMOUNT_KOPECKS) long amountKopecks,
        @NotNull @PastOrPresent LocalDateTime effectiveAt,
        @NotBlank @Size(max = 255) String reason,
        /** Identifier of an internal document; never raw names, phone or card/account data. */
        @NotBlank @Size(max = 160) String evidenceReference,
        @NotBlank @Size(max = 120) String idempotencyKey
) {
}
