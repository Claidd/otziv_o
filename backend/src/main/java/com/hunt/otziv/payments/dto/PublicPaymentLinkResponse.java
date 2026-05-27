package com.hunt.otziv.payments.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PublicPaymentLinkResponse(
        String token,
        Long orderId,
        String companyTitle,
        String filialTitle,
        String serviceTitle,
        BigDecimal amount,
        long amountKopecks,
        String description,
        String payerEmail,
        String status,
        String paymentMethod,
        LocalDateTime expiresAt,
        boolean payable,
        String paymentPageMode,
        boolean tpayEnabled,
        boolean sberpayEnabled,
        boolean mirpayEnabled,
        String manualPaymentType,
        String manualPhone,
        String manualRecipientName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel,
        String manualComment,
        String receiptStatus
) {
}
