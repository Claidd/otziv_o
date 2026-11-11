package com.hunt.otziv.common_billing.dto;

import java.util.List;

/**
 * The «Вернуть заказ в счёт» window of a common invoice. {@code orders} are unattached orders of
 * the invoice's own link; when {@code canReturn} is false the list is empty and
 * {@code blockReason} says why the composition is fixed and what to do instead.
 */
public record CommonInvoiceReturnableOrdersResponse(
        Long invoiceId,
        boolean canReturn,
        String blockReason,
        List<Item> orders
) {
    /** {@code amountKopecks} is what the order would add to the invoice; 0 when it cannot be calculated. */
    public record Item(
            Long orderId,
            Long companyId,
            String companyTitle,
            String orderTitle,
            String status,
            long amountKopecks
    ) {
    }
}
