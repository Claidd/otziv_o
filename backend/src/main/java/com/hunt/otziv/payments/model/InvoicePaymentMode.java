package com.hunt.otziv.payments.model;

/**
 * Business-level invoice policy. AUTO_ROUTING lets every new payment attempt
 * use the current contractor routing decision. The other values are explicit
 * operator choices and must survive refreshes, reminders and invoice rebuilds.
 */
public enum InvoicePaymentMode {
    AUTO_ROUTING,
    EMPLOYEE_REQUISITES,
    OWNER_TBANK,
    OWNER_PAPER_INVOICE
}
