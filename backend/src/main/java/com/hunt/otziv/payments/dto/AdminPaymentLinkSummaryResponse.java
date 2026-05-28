package com.hunt.otziv.payments.dto;

import java.math.BigDecimal;

public record AdminPaymentLinkSummaryResponse(
        long totalElements,
        BigDecimal totalAmount,
        long totalAmountKopecks,
        long paid,
        long manualPending,
        long confirmed,
        long notificationsSent,
        long notificationErrors,
        long refundable,
        long refunded,
        long rejected
) {
}
