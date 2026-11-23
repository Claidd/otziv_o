-- When a paid common invoice was reopened back to "Напоминание" (a payment marked by mistake), its payment term
-- starts again from that moment: the automatic move to "Не оплачено" counts from the later of the invoice's own
-- term start and this time, and a new overdue notice is required. NULL = the invoice was never reopened.
ALTER TABLE common_invoices
    ADD COLUMN overdue_term_restarted_at DATETIME(6) NULL;
