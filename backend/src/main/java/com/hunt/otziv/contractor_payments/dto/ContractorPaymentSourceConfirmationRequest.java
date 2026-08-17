package com.hunt.otziv.contractor_payments.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;

/** Audited statement evidence bound to one immutable payment source. */
public record ContractorPaymentSourceConfirmationRequest(
        @NotNull @AssertTrue Boolean recipientStatementChecked,
        @NotNull @AssertTrue Boolean paymentReceived,
        @NotNull @Positive Long confirmedTotalKopecks,
        LocalDateTime effectiveAt,
        @NotBlank String reason
) {
}
