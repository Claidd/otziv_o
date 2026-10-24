package com.hunt.otziv.common_billing.api;

/** Outcome of checking an existing invoice delivery, without creating another send. */
public record CommonInvoiceMessageRecovery(boolean confirmed, boolean pending, String message) {}
