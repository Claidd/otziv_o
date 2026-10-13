package com.hunt.otziv.common_billing.dto;

/**
 * Route changes that are valid specifically for a common invoice.
 *
 * <p>The explicit reissue command intentionally does not belong to the ordinary-order
 * payment route API: it preserves the common invoice owner allocation while refreshing
 * only its frozen bank profile.</p>
 */
public enum CommonInvoicePaymentRouteChangeTarget {
    EMPLOYEE_REQUISITES,
    OWNER_TBANK,
    OWNER_BANK_REISSUE
}
