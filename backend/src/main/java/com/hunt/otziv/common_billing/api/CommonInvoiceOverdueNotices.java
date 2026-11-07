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

    /**
     * {@code sentAt} identifies the invoice's sending cycle for {@link #recordNotice}; the payment term
     * runs from {@code termStart}: the sending, or for a bank paper invoice the delivery of its documents.
     */
    record Approaching(
            long invoiceId,
            long managerId,
            String accountName,
            long remainingKopecks,
            LocalDateTime sentAt,
            long unpaidDays,
            LocalDateTime moveNotBefore,
            LocalDateTime termStart,
            boolean paperDelivery,
            int extensionDays
    ) {
        public Approaching(long invoiceId, long managerId, String accountName, long remainingKopecks,
                LocalDateTime sentAt, long unpaidDays, LocalDateTime moveNotBefore) {
            this(invoiceId, managerId, accountName, remainingKopecks, sentAt, unpaidDays, moveNotBefore, sentAt, false, 0);
        }
    }

    /** Empty while the automatic move is switched off, paused or otherwise not allowed. */
    List<Approaching> pendingNotices(LocalDateTime now);

    /** False when the invoice was paid, resent or changed since it was listed. */
    boolean recordNotice(long invoiceId, LocalDateTime listedSentAt, LocalDateTime noticeAt);

    /**
     * Earliest automatic move of this invoice to "Не оплачено", counting the notice period from
     * the recorded notice or, before it, from now. Empty when the autoresponder will not move it
     * (paper invoice not yet delivered, part-paid invoice, client reported a payment, switches off or paused).
     */
    java.util.Optional<LocalDateTime> automaticMoveNotBefore(long invoiceId, LocalDateTime now);

    /**
     * Start of the invoice's payment term: its sending, or for a bank paper invoice the confirmed
     * delivery of all its documents (empty until then). The term ends after the autoresponder's
     * overdue days plus the invoice's own extension.
     */
    java.util.Optional<LocalDateTime> termStart(long invoiceId);
}
