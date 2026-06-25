package com.hunt.otziv.common_billing.dto;

import java.util.List;

public record CommonBillingAccountResponse(
        Long id,
        String name,
        boolean enabled,
        boolean autoRepeatOrders,
        Long managerId,
        String managerName,
        Long invoiceCompanyId,
        String invoiceCompanyTitle,
        List<CommonBillingCompanyResponse> companies,
        CommonInvoiceSummaryResponse currentInvoice
) {
}
