package com.hunt.otziv.payments.model;

/**
 * Business-level invoice policy. AUTO_ROUTING keeps the existing contractor
 * routing untouched; OWNER_PAPER_INVOICE is an explicit owner/admin override
 * that never exposes employee requisites or starts T-Bank.
 */
public enum InvoicePaymentMode {
    AUTO_ROUTING,
    OWNER_PAPER_INVOICE
}
