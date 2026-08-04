ALTER TABLE common_invoice_orders
    ADD COLUMN source_payment_link_id BIGINT NULL AFTER payment_method,
    ADD INDEX idx_common_invoice_orders_source_payment_link (source_payment_link_id);

ALTER TABLE payment_links
    ADD COLUMN provider_terminal_status VARCHAR(32) NULL AFTER last_error;
