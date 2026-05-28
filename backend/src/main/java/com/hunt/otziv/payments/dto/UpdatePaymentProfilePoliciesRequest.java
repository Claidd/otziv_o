package com.hunt.otziv.payments.dto;

import java.util.List;

public record UpdatePaymentProfilePoliciesRequest(
        List<PaymentProfilePolicyRequest> profiles
) {
}
