package com.hunt.otziv.paper_billing;

import com.hunt.otziv.payments.api.PaperInvoiceReminderProgress;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Reads the per-document reminder clock that {@link PaperBillingReminders} drives; never sends. */
@Service
@RequiredArgsConstructor
public class PaperBillingReminderProgress implements PaperInvoiceReminderProgress {

    private static final String PROGRESS = """
            SELECT COUNT(*) AS open_documents,
                   COALESCE(SUM(CASE WHEN stage = 'WAITING_PAYMENT'
                                      AND paper_reminder_state = 'PENDING'
                                      AND paper_next_reminder_at IS NOT NULL
                                      AND paper_next_reminder_at > ?
                                     THEN 1 ELSE 0 END), 0) AS on_schedule
            FROM paper_invoice_document
            WHERE target_kind = 'COMMON_PART'
              AND parent_invoice_id = ?
              AND paid_at IS NULL
              AND cancel_requested_at IS NULL
              AND cancelled_at IS NULL
            """;

    private final PaperBillingStore store;

    @Override
    public boolean remindersOnSchedule(long commonInvoiceId, LocalDateTime staleReminderBefore) {
        if (staleReminderBefore == null) {
            return false;
        }
        Map<String, Object> row = store.jdbc().queryForMap(PROGRESS, staleReminderBefore, commonInvoiceId);
        long open = ((Number) row.get("open_documents")).longValue();
        long onSchedule = ((Number) row.get("on_schedule")).longValue();
        return open > 0 && onSchedule == open;
    }
}
