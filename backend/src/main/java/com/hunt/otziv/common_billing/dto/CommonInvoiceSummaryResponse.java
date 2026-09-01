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
        LocalDateTime closedAt,
        String closedBy,
        String closeReason,
        String lastError,
        String paymentSuccessNotificationError,
        String tbankOrderId,
        String tbankPaymentId,
        Long tbankPaymentAmountKopecks,
        String tbankTerminalLabel,
        String tbankTerminalKey,
        String paymentRouteType,
        String paymentRouteProvider,
        String paymentRouteProfileName,
        String paymentRouteRecipient,
        Long paymentRouteManualTaskId,
        boolean contractorPaymentRoute,
        LocalDateTime paymentRouteSelectedAt,
        String invoicePurpose,
        Long supersedesInvoiceId,
        String invoicePaymentMode,
        LocalDateTime paperInvoiceIssuedAt
) {
    public CommonInvoiceSummaryResponse(
            Long id, Long accountId, String accountName, String title, String token, String publicUrl,
            String status, int totalOrders, int readyOrders, int paidOrders,
            BigDecimal amount, BigDecimal paid, BigDecimal remaining,
            long amountKopecks, long paidKopecks, long remainingKopecks,
            LocalDateTime sentAt, LocalDateTime lastReminderAt, LocalDateTime nextReminderAt,
            LocalDateTime closedAt, String closedBy, String closeReason, String lastError,
            String paymentSuccessNotificationError, String tbankOrderId, String tbankPaymentId,
            Long tbankPaymentAmountKopecks, String tbankTerminalLabel, String tbankTerminalKey,
            String paymentRouteType, String paymentRouteProfileName, Long paymentRouteManualTaskId,
            boolean contractorPaymentRoute, LocalDateTime paymentRouteSelectedAt,
            String invoicePurpose, Long supersedesInvoiceId
    ) {
        this(id, accountId, accountName, title, token, publicUrl, status, totalOrders, readyOrders,
                paidOrders, amount, paid, remaining, amountKopecks, paidKopecks, remainingKopecks,
                sentAt, lastReminderAt, nextReminderAt, closedAt, closedBy, closeReason, lastError,
                paymentSuccessNotificationError, tbankOrderId, tbankPaymentId,
                tbankPaymentAmountKopecks, tbankTerminalLabel, tbankTerminalKey, paymentRouteType,
                null, paymentRouteProfileName, null, paymentRouteManualTaskId, contractorPaymentRoute,
                paymentRouteSelectedAt, invoicePurpose, supersedesInvoiceId,
                "AUTO_ROUTING", null);
    }
}
