package com.hunt.otziv.contractor_payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorAccrualAdjustmentKind;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAmountLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ContractorAccrualAdjustmentRequest(
        @NotNull ContractorAccrualAdjustmentKind kind,
        @Min(1) @Max(ContractorPaymentAmountLimits.MAX_AMOUNT_KOPECKS) long amountKopecks,
        @NotBlank @Size(max = 255) String reason,
        @NotBlank String referenceMonth,
        @Size(max = 160) String evidenceReference,
        @Size(max = 120) String idempotencyKey
) {
}
