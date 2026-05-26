package com.hunt.otziv.payments.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PublicPaymentInitRequest(
        @NotBlank
        @Email
        String email,
        Boolean offerConsent,
        Boolean privacyConsent,
        Boolean receiptConsent
) {
}
