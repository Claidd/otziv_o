package com.hunt.otziv.common_billing.dto;

public record CommonBillingCompanyResponse(
        Long companyId,
        String companyTitle,
        boolean enabled
) {
}
