-- A manager-granted extension of one common invoice's payment term before the automatic move to
-- "Не оплачено": the move happens after the autoresponder's overdue days plus these days.
-- Who granted it and when are kept on the invoice for the card and the control board.
ALTER TABLE common_invoices
    ADD COLUMN overdue_extension_days INT NOT NULL DEFAULT 0,
    ADD COLUMN overdue_extension_by VARCHAR(150) NULL,
    ADD COLUMN overdue_extension_at DATETIME(6) NULL;
