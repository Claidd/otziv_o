package com.hunt.otziv.payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Compare-and-swap identity for correcting a mistaken owner receipt report. */
public record RevokePendingOwnerApprovalRequest(
        @NotNull @Positive Long expectedOrderId,
        @NotNull @Positive Long expectedPaymentLinkId,
        @NotBlank @Size(max = 500) String reason
) {}
