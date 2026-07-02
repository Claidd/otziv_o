package com.hunt.otziv.payments.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ManualPaymentRecipientMonthlySummaryResponse(
        String month,
        LocalDate from,
        LocalDate toExclusive,
        long totalRecipients,
        long totalPayments,
        long totalAmountKopecks,
        BigDecimal totalAmount,
        List<ManualPaymentRecipientMonthlySummaryItem> items
) {
}
