-- Additive scheduler cursor. Existing rows remain immediately eligible and
-- are rotated after their first reconciliation attempt.
ALTER TABLE payment_links
    ADD COLUMN bank_reconciliation_attempted_at DATETIME(6) NULL,
    ADD INDEX idx_payment_links_bank_reconciliation_due
        (status, bank_reconciliation_attempted_at, updated_at, id),
    ALGORITHM=INPLACE,
    LOCK=NONE;
