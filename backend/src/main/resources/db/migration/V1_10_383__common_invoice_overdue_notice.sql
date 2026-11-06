-- When the manager was told that an unpaid common invoice will move to "Не оплачено".
-- A notice older than the invoice's current sent_at belongs to an earlier sending cycle.
ALTER TABLE common_invoices
    ADD COLUMN overdue_notice_at DATETIME(6) NULL;
