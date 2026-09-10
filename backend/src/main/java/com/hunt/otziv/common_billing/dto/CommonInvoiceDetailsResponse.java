package com.hunt.otziv.common_billing.dto;

import com.hunt.otziv.p_products.dto.OrderDTOList;
import java.util.List;

public record CommonInvoiceDetailsResponse(
        CommonInvoiceSummaryResponse summary,
        List<CommonInvoiceOrderResponse> orders,
        List<OrderDTOList> orderCards,
        List<CommonInvoiceNextCycleResponse> nextCycleOrders,
        List<CommonInvoicePaymentRefResponse> paymentRefs,
        String paymentEvidenceToken,
        com.hunt.otziv.client_messages.api.DeliveryOperation delivery
) {
    public CommonInvoiceDetailsResponse(CommonInvoiceSummaryResponse summary, List<CommonInvoiceOrderResponse> orders,
            List<OrderDTOList> orderCards, List<CommonInvoiceNextCycleResponse> nextCycleOrders,
            List<CommonInvoicePaymentRefResponse> paymentRefs, String paymentEvidenceToken) {
        this(summary, orders, orderCards, nextCycleOrders, paymentRefs, paymentEvidenceToken, null);
    }
}
