package com.hunt.otziv.payments.dto;

import java.util.List;

public record AdminPaymentLinksPageResponse(
        List<AdminPaymentLinkResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String source,
        AdminPaymentLinkSummaryResponse summary
) {
}
