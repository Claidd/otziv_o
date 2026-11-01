ALTER TABLE paper_invoice_document
    ADD COLUMN cancel_replacement_state VARCHAR(16) NULL,
    ADD COLUMN cancel_replacement_error VARCHAR(500) NULL,
    ADD COLUMN cancel_replacement_queued_at DATETIME(6) NULL;
