package com.hunt.otziv.common_billing.dto;

import com.hunt.otziv.payments.dto.PaymentRouteChangeTarget;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CommonInvoicePaymentRouteChangeRequest(
        @NotNull PaymentRouteChangeTarget target,
        boolean confirmedUnpaid,
        @NotBlank String expectedPaymentEvidenceToken
) {
}
