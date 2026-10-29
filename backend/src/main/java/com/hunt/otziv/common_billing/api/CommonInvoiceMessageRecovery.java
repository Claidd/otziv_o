package com.hunt.otziv.common_billing.api;

/** Outcome of checking an existing invoice delivery, without creating another send. */
public record CommonInvoiceMessageRecovery(boolean confirmed, boolean pending, String message,
        java.time.LocalDateTime nextReminderAt) {
    public CommonInvoiceMessageRecovery(boolean confirmed, boolean pending, String message) {
        this(confirmed, pending, message, null);
    }
}
