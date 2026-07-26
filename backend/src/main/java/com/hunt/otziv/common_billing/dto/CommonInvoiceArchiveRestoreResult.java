package com.hunt.otziv.common_billing.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CommonInvoiceArchiveRestoreResult(
        Long invoiceId,
        String status,
        String source,
        LocalDateTime restoredAt,
        String restoredBy,
        List<Long> orderIds,
        String message
) {
}

