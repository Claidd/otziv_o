package com.hunt.otziv.common_billing.dto;

import com.hunt.otziv.p_products.dto.OrderDTOList;
import java.util.List;

public record CommonInvoiceDetailsResponse(
        CommonInvoiceSummaryResponse summary,
        List<CommonInvoiceOrderResponse> orders,
        List<OrderDTOList> orderCards
) {
}
