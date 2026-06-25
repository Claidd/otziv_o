package com.hunt.otziv.common_billing.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CommonInvoiceSummaryResponse(
        Long id,
        Long accountId,
        String accountName,
        String title,
        String token,
        String publicUrl,
        String status,
        int totalOrders,
        int readyOrders,
        int paidOrders,
        BigDecimal amount,
        BigDecimal paid,
        BigDecimal remaining,
        long amountKopecks,
        long paidKopecks,
        long remainingKopecks,
        LocalDateTime sentAt,
        LocalDateTime lastReminderAt,
        LocalDateTime nextReminderAt,
        String lastError,
        String paymentSuccessNotificationError
) {
}
