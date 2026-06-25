package com.hunt.otziv.common_billing.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CommonInvoiceOrderResponse(
        Long orderId,
        Long companyId,
        String companyTitle,
        String filialTitle,
        String orderStatus,
        String originalOrderStatus,
        BigDecimal amount,
        long amountKopecks,
        boolean ready,
        boolean paid,
        boolean unpaid,
        boolean detachable,
        LocalDateTime paidAt
) {
}
