package com.hunt.otziv.common_billing.dto;

public record CommonInvoiceArchiveOrderItem(
        Long orderId,
        String companyTitle,
        String filialTitle,
        String status,
        String archiveSourceStatus,
        long amountKopecks,
        boolean paid
) {
}

