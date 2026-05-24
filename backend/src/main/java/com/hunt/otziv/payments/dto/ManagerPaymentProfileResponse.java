package com.hunt.otziv.payments.dto;

public record ManagerPaymentProfileResponse(
        Long managerId,
        String managerTitle,
        String username,
        Long paymentProfileId,
        String paymentProfileName
) {
}
