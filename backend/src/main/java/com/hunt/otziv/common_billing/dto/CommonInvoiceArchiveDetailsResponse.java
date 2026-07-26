package com.hunt.otziv.common_billing.dto;

import java.util.List;

public record CommonInvoiceArchiveDetailsResponse(
        CommonInvoiceArchiveListItem invoice,
        List<CommonInvoiceArchiveOrderItem> orders
) {
}

