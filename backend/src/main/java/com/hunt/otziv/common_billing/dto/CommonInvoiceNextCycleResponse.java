package com.hunt.otziv.common_billing.dto;

public record CommonInvoiceNextCycleResponse(
        Long sourceOrderId,
        Long orderId,
        Long invoiceId,
        String invoiceStatus,
        String companyTitle,
        String filialTitle,
        String orderStatus
) {
}
