package com.hunt.otziv.common_billing.dto;

import java.time.LocalDateTime;

public record CommonInvoiceArchiveListItem(
        Long id,
        String accountName,
        String title,
        String status,
        long amountKopecks,
        long paidKopecks,
        int orderCount,
        LocalDateTime closedAt,
        String closedBy,
        String closeReason,
        LocalDateTime archivedAt,
        String source,
        boolean restorable
) {
}

