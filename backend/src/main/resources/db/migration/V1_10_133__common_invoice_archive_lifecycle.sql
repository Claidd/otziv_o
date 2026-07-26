ALTER TABLE common_invoices
    ADD COLUMN closed_at DATETIME(6) NULL AFTER paid_at,
    ADD COLUMN closed_by VARCHAR(160) NULL AFTER closed_at,
    ADD COLUMN close_reason VARCHAR(32) NULL AFTER closed_by,
    ADD COLUMN previous_status VARCHAR(32) NULL AFTER close_reason;

ALTER TABLE common_invoice_orders
    ADD COLUMN archive_source_order_status_title VARCHAR(64) NULL AFTER original_order_status_title;

CREATE INDEX idx_common_invoices_closed
    ON common_invoices (status, closed_at, invoice_id);

CREATE TABLE IF NOT EXISTS archive_common_invoices LIKE common_invoices;
CREATE TABLE IF NOT EXISTS archive_common_invoice_orders LIKE common_invoice_orders;
CREATE TABLE IF NOT EXISTS archive_common_invoice_payment_refs LIKE common_invoice_payment_refs;

ALTER TABLE archive_common_invoices
    MODIFY invoice_id BIGINT NOT NULL,
    ADD COLUMN archived_at DATETIME(6) NULL,
    ADD COLUMN archive_reason VARCHAR(100) NULL,
    ADD COLUMN archive_batch_id BIGINT NULL,
    ADD COLUMN restored_at DATETIME(6) NULL,
    ADD COLUMN restored_by VARCHAR(255) NULL,
    ADD COLUMN restore_batch_id BIGINT NULL;

ALTER TABLE archive_common_invoice_orders
    MODIFY invoice_order_id BIGINT NOT NULL,
    ADD COLUMN archived_at DATETIME(6) NULL,
    ADD COLUMN archive_reason VARCHAR(100) NULL,
    ADD COLUMN archive_batch_id BIGINT NULL;

ALTER TABLE archive_common_invoice_payment_refs
    MODIFY payment_ref_id BIGINT NOT NULL,
    ADD COLUMN archived_at DATETIME(6) NULL,
    ADD COLUMN archive_reason VARCHAR(100) NULL,
    ADD COLUMN archive_batch_id BIGINT NULL;

CREATE INDEX idx_archive_common_invoices_closed
    ON archive_common_invoices (restored_at, closed_at, invoice_id);
CREATE INDEX idx_archive_common_invoice_orders_invoice
    ON archive_common_invoice_orders (invoice_id, order_id);
CREATE INDEX idx_archive_common_invoice_payment_refs_invoice
    ON archive_common_invoice_payment_refs (invoice_id, payment_ref_id);

