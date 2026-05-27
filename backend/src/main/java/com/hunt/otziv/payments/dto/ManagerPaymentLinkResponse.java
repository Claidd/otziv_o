package com.hunt.otziv.payments.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ManagerPaymentLinkResponse(
        String token,
        String url,
        Long orderId,
        BigDecimal amount,
        long amountKopecks,
        String status,
        String paymentMethod,
        LocalDateTime expiresAt,
        String instructionText,
        String copyText
) {
}
