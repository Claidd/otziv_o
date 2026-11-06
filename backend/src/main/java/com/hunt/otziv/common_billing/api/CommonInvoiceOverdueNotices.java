package com.hunt.otziv.common_billing.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Billing-owned list of unpaid common invoices the autoresponder is about to move to
 * "Не оплачено", for the manager's group notice. The move itself happens only after the notice
 * is recorded and the notice period has passed; nothing here sends or changes an invoice except
 * {@link #recordNotice}, which stores the notice time for the invoice's current sending cycle.
 */
public interface CommonInvoiceOverdueNotices {

    /** Days between the manager notice and the automatic move. */
    int NOTICE_DAYS = 2;

    record Approaching(
            long invoiceId,
            long managerId,
            String accountName,
            long remainingKopecks,
            LocalDateTime sentAt,
            long unpaidDays,
            LocalDateTime moveNotBefore
    ) {}

    /** Empty while the automatic move is switched off, paused or otherwise not allowed. */
    List<Approaching> pendingNotices(LocalDateTime now);

    /** False when the invoice was paid, resent or changed since it was listed. */
    boolean recordNotice(long invoiceId, LocalDateTime listedSentAt, LocalDateTime noticeAt);

    /**
     * Earliest automatic move of this invoice to "Не оплачено", counting the notice period from
     * the recorded notice or, before it, from now. Empty when the autoresponder will not move it
     * (bank paper or part-paid invoice, client reported a payment, switches off or paused).
     */
    java.util.Optional<LocalDateTime> automaticMoveNotBefore(long invoiceId, LocalDateTime now);
}
