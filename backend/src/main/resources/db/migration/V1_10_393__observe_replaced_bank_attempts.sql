ALTER TABLE payment_links
    ADD COLUMN bank_expires_at DATETIME(6) NULL,
    ADD COLUMN observe_replaced_bank BOOLEAN NOT NULL DEFAULT FALSE,
    ADD INDEX ix_payment_link_bank_watch (observe_replaced_bank, bank_reconciliation_attempted_at);

ALTER TABLE common_invoice_payment_refs
    ADD COLUMN observe_replaced_bank BOOLEAN NOT NULL DEFAULT FALSE,
    ADD INDEX ix_common_ref_bank_watch (observe_replaced_bank, next_provider_check_at);

UPDATE common_invoice_payment_refs SET observe_replaced_bank=TRUE
WHERE status='ARCHIVED' AND reason='actual_recipient_received_observe_only';
