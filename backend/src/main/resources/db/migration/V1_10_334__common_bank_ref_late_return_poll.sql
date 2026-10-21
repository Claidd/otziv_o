-- A paid common-invoice attempt remains eligible for provider GET after the webhook
-- window. A durable next-check timestamp rotates old refs fairly across workers.
ALTER TABLE common_invoice_payment_refs
    ADD COLUMN next_provider_check_at DATETIME(6) NULL;

ALTER TABLE archive_common_invoice_payment_refs
    ADD COLUMN next_provider_check_at DATETIME(6) NULL;

CREATE INDEX idx_common_payment_refs_settled_provider_check
    ON common_invoice_payment_refs (status, next_provider_check_at, payment_ref_id);
