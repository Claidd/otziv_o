package com.hunt.otziv.payments.dto;

import com.hunt.otziv.payments.model.PaymentMethod;
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
        String copyText,
        String telegramCopyTransferNumber
) {
    public ManagerPaymentLinkResponse(
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
        this(token, url, orderId, amount, amountKopecks, status, paymentMethod,
                expiresAt, instructionText, copyText, null);
    }
}
