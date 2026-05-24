package com.hunt.otziv.payments.dto;

public record ManagerPaymentProfileAssignmentRequest(
        Long managerId,
        Long paymentProfileId
) {
}
