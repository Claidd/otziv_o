package com.hunt.otziv.payments;

public enum PaymentLinkStatus {
    CREATED,
    INITIATED,
    AUTHORIZED,
    WAITING_MANUAL_PAYMENT,
    MANUAL_REPORTED,
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
