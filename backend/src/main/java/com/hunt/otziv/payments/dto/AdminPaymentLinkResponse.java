package com.hunt.otziv.payments.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AdminPaymentLinkResponse(
        Long id,
        String token,
        String publicUrl,
        Long orderId,
        String companyTitle,
        String filialTitle,
        String description,
        BigDecimal amount,
        long amountKopecks,
        String status,
        String paymentProfileCode,
        String paymentProfileName,
        String tbankTerminalKey,
        String tbankPaymentId,
        String tbankOrderId,
        String payerEmail,
        String paymentUrl,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime expiresAt,
        LocalDateTime initiatedAt,
        LocalDateTime paidAt,
        boolean refundable
) {
}
