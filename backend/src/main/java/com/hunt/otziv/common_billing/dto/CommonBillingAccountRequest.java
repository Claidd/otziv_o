package com.hunt.otziv.common_billing.dto;

import java.util.List;

public record CommonBillingAccountRequest(
        String name,
        Boolean enabled,
        Boolean autoRepeatOrders,
        Long managerId,
        Long invoiceCompanyId,
        List<Long> companyIds
) {
}
