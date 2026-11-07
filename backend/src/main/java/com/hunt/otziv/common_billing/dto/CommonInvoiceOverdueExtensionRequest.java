package com.hunt.otziv.common_billing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Extra days before the automatic move to "Не оплачено"; replaces the previous value, 0 removes it. */
public record CommonInvoiceOverdueExtensionRequest(
        @NotNull @Min(0) @Max(60) Integer extensionDays
) {
}
