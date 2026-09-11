package com.hunt.otziv.p_products.api;

/** Raised before allocating an operation or contacting any delivery provider. */
public final class LegacyOrderNotificationException extends IllegalStateException {
    public LegacyOrderNotificationException() { super("legacy_operation_unverified"); }
}
