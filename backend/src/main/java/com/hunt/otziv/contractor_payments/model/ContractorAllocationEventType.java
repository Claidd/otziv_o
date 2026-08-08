package com.hunt.otziv.contractor_payments.model;

public enum ContractorAllocationEventType {
    RESERVED,
    CLIENT_REPORTED,
    CONFIRMED,
    SIMULATED_CONFIRMED,
    RELEASED,
    EXPIRED,
    CANCELED,
    RETURNED,
    RETURN_AMOUNT_PENDING,
    OWNER_FALLBACK
}
