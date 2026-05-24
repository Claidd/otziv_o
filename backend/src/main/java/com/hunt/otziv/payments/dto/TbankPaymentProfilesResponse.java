package com.hunt.otziv.payments.dto;

import java.util.List;

public record TbankPaymentProfilesResponse(
        List<PaymentProfileResponse> profiles,
        List<ManagerPaymentProfileResponse> managers
) {
}
