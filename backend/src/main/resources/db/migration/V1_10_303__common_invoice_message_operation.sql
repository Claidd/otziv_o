-- A committed invoice message occurrence owns its ID across failed/ambiguous delivery.
-- Existing timestamps are business/UI state and cannot identify a retried provider operation.
ALTER TABLE common_invoices
    ADD COLUMN payment_message_operation_id VARCHAR(128) NULL,
    ADD COLUMN payment_message_operation_kind VARCHAR(32) NULL,
    ADD COLUMN payment_message_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN payment_message_channel VARCHAR(32) NULL;
