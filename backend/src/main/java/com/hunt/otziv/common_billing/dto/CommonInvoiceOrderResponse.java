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
        LocalDateTime paidAt,
        String paymentMethod,
        String paidBy,
        String paymentComment,
        String paymentReceiptUrl,
        String detachBlockedReason
) {
    public CommonInvoiceOrderResponse(Long orderId, Long companyId, String companyTitle, String filialTitle,
            String orderStatus, String originalOrderStatus, BigDecimal amount, long amountKopecks,
            boolean ready, boolean paid, boolean unpaid, boolean detachable, LocalDateTime paidAt,
            String paymentMethod, String paidBy, String paymentComment, String paymentReceiptUrl) {
        this(orderId, companyId, companyTitle, filialTitle, orderStatus, originalOrderStatus, amount,
                amountKopecks, ready, paid, unpaid, detachable, paidAt, paymentMethod, paidBy,
                paymentComment, paymentReceiptUrl, null);
    }
}
