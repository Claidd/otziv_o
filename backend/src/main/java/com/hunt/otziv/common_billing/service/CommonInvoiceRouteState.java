package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.payments.model.InvoicePaymentMode;

/** Pure predicates shared by invoice presentation and payment workflows. */
final class CommonInvoiceRouteState {
    private CommonInvoiceRouteState() {}

    static boolean isOwnerPaperInvoice(CommonInvoice invoice) {
        return invoice != null && invoice.getInvoicePaymentMode() == InvoicePaymentMode.OWNER_PAPER_INVOICE;
    }

    static boolean hasFrozenCommonPaymentRoute(CommonInvoice invoice) {
        return invoice != null && invoice.getPaymentRouteSelectedAt() != null
                && invoice.getPaymentRouteType() != null && !invoice.getPaymentRouteType().trim().isBlank();
    }
}
