package com.hunt.otziv.common_billing.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
        String paymentRouteType,
        String manualPaymentType,
        String manualPhone,
        String manualRecipientName,
        String manualBankName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel,
        String manualComment,
        String paymentInstructionText,
        boolean clientReportable,
        LocalDateTime clientReportedAt,
        List<CommonInvoiceOrderResponse> orders
) {
}
