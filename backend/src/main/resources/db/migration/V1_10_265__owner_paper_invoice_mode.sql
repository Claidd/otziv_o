ALTER TABLE orders
    ADD COLUMN invoice_payment_mode VARCHAR(32) NOT NULL DEFAULT 'AUTO_ROUTING';

ALTER TABLE payment_links
    ADD COLUMN paper_invoice_issued_at DATETIME(6) NULL;

ALTER TABLE common_billing_accounts
    ADD COLUMN invoice_payment_mode VARCHAR(32) NOT NULL DEFAULT 'AUTO_ROUTING';

ALTER TABLE common_invoices
    ADD COLUMN invoice_payment_mode VARCHAR(32) NOT NULL DEFAULT 'AUTO_ROUTING',
    ADD COLUMN paper_invoice_issued_at DATETIME(6) NULL;

CREATE INDEX idx_orders_invoice_payment_mode
    ON orders (invoice_payment_mode, order_status, order_id);

CREATE INDEX idx_common_invoices_payment_mode
    ON common_invoices (invoice_payment_mode, status, invoice_id);
