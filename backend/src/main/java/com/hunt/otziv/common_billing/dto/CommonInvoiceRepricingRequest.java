package com.hunt.otziv.common_billing.dto;

import jakarta.validation.constraints.*;

public record CommonInvoiceRepricingRequest(@NotBlank @Size(max = 160) String operationId,
        @Positive long previousKopecks, @Positive long currentKopecks, @AssertTrue boolean statementChecked) {}
