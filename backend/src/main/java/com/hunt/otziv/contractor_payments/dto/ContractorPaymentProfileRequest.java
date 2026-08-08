package com.hunt.otziv.contractor_payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorRole;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ContractorPaymentProfileRequest(
        @NotNull ContractorRole role,
        @NotNull @Min(0) Long expectedVersion,
        boolean enabled,
        boolean liveEnabled,
        String recipientName,
        String paymentPhone,
        String bankName,
        String paymentComment,
        long openingBalanceKopecks,
        @Size(max = 255) String openingBalanceReason
) {
}
