package com.hunt.otziv.common_billing.dto;

import java.math.BigDecimal;
import java.util.List;

public record PublicCommonInvoiceResponse(
        String token,
        String title,
        String accountName,
        String status,
        BigDecimal amount,
        BigDecimal paid,
        BigDecimal remaining,
        long amountKopecks,
        long paidKopecks,
        long remainingKopecks,
        boolean payable,
        List<CommonInvoiceOrderResponse> orders
) {
}
