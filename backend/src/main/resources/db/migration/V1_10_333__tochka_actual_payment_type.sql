-- Keep the actual acquiring payment type separate from the mode(s) offered by the link.
-- A digital-ruble payment may be approved on a link created with the SBP mode.
ALTER TABLE payment_links
    ADD COLUMN provider_payment_type VARCHAR(32) NULL AFTER provider_terminal_status;

ALTER TABLE archive_payment_links
    ADD COLUMN provider_payment_type VARCHAR(32) NULL AFTER last_error;

ALTER TABLE common_invoice_payment_refs
    ADD COLUMN provider_payment_type VARCHAR(32) NULL AFTER provider_payment_mode;

ALTER TABLE archive_common_invoice_payment_refs
    ADD COLUMN provider_payment_type VARCHAR(32) NULL AFTER provider_payment_mode;
