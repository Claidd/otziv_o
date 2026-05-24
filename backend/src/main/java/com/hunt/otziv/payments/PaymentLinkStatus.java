package com.hunt.otziv.payments;

public enum PaymentLinkStatus {
    CREATED,
    INITIATED,
    AUTHORIZED,
    TEST_CONFIRMED,
    CONFIRMED,
    REJECTED,
    CANCELED,
    REVERSED,
    PARTIAL_REVERSED,
    REFUNDED,
    PARTIAL_REFUNDED,
    EXPIRED,
    FAILED
}
