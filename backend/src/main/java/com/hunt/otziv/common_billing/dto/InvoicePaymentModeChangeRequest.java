package com.hunt.otziv.common_billing.dto;

import com.hunt.otziv.payments.model.InvoicePaymentMode;
import jakarta.validation.constraints.NotNull;

public record InvoicePaymentModeChangeRequest(
        @NotNull InvoicePaymentMode mode,
        boolean confirmedUnpaid
) {
}
