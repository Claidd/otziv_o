package com.hunt.otziv.common_billing.dto;

import java.util.List;

public record CommonInvoiceArchivePreviewResponse(
        Long invoiceId,
        boolean allowed,
        int totalOrders,
        List<CommonInvoiceArchiveOrderPreview> orders,
        List<String> blockers
) {
    public record CommonInvoiceArchiveOrderPreview(
            Long orderId,
            String companyTitle,
            String status,
            boolean allowed,
            List<String> blockers
    ) {
    }
}

