package com.hunt.otziv.common_billing.service;

/**
 * Result of locking a common-invoice position before its payable amount changes.
 */
public enum CommonPayableChangeDisposition {
    NOT_LINKED,
    REFRESH_CURRENT_INVOICE,
    SUPPLEMENT_REQUIRED
}
