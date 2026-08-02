package com.hunt.otziv.payments.dto;

/** Explicit operator assertion made only after checking the provider cabinet. */
public record ResolveAmbiguousBankInitRequest(
        Boolean bankPaymentAbsent,
        String note
) {
}
