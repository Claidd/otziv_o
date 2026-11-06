package com.hunt.otziv.payments.api;

import java.time.LocalDateTime;

/**
 * Read-only view of automatic payment reminders for the bank paper documents of a common invoice.
 * A paper common invoice pauses the invoice's own reminder clock, and each document carries its
 * own; staff boards use this to tell a working reminder cycle from a stalled or finished one.
 */
public interface PaperInvoiceReminderProgress {

    /**
     * True when the common invoice has at least one unpaid, not cancelled document and every such
     * document waits for payment with its next automatic reminder scheduled after
     * {@code staleReminderBefore}. Finished, stalled or unknown reminders return false.
     */
    boolean remindersOnSchedule(long commonInvoiceId, LocalDateTime staleReminderBefore);
}
