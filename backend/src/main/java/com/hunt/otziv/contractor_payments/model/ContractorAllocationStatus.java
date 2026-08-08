package com.hunt.otziv.contractor_payments.model;

public enum ContractorAllocationStatus {
    RESERVED,
    CLIENT_REPORTED,
    PARTIALLY_CONFIRMED,
    CONFIRMED,
    SIMULATED_PAID,
    LATE_PAYMENT_AFTER_RELEASE,
    OWNER_FALLBACK,
    RELEASED_UNPAID,
    EXPIRED,
    CANCELED,
    PARTIALLY_RETURNED,
    RETURN_AMOUNT_PENDING,
    RETURNED
}
