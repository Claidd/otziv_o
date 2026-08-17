ALTER TABLE common_invoice_orders
    ADD COLUMN active_membership BOOLEAN NOT NULL DEFAULT TRUE AFTER invoice_id,
    ADD COLUMN active_order_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN active_membership THEN order_id ELSE NULL END) STORED
        AFTER order_id;

UPDATE common_invoice_orders
SET active_membership = TRUE
WHERE active_membership IS NULL;

ALTER TABLE common_invoice_orders
    DROP INDEX uk_common_invoice_order,
    ADD UNIQUE INDEX uk_common_invoice_active_order (active_order_id),
    ADD INDEX idx_common_invoice_order_history (order_id, active_membership, invoice_id);

ALTER TABLE common_invoices
    ADD COLUMN supersedes_invoice_id BIGINT NULL AFTER account_id,
    ADD COLUMN invoice_purpose VARCHAR(32) NOT NULL DEFAULT 'STANDARD' AFTER supersedes_invoice_id,
    ADD COLUMN cycle_idempotency_key VARCHAR(160) NULL AFTER invoice_purpose,
    ADD UNIQUE INDEX uk_common_invoice_cycle_idempotency (cycle_idempotency_key),
    ADD INDEX idx_common_invoice_supersedes (supersedes_invoice_id),
    ADD CONSTRAINT fk_common_invoice_supersedes
        FOREIGN KEY (supersedes_invoice_id) REFERENCES common_invoices (invoice_id);

ALTER TABLE archive_common_invoice_orders
    ADD COLUMN active_membership BOOLEAN NOT NULL DEFAULT TRUE AFTER invoice_id,
    DROP INDEX uk_common_invoice_order,
    ADD UNIQUE INDEX uk_archive_common_invoice_order_cycle (invoice_id, order_id);

UPDATE archive_common_invoice_orders
SET active_membership = TRUE
WHERE active_membership IS NULL;

ALTER TABLE archive_common_invoices
    ADD COLUMN supersedes_invoice_id BIGINT NULL AFTER account_id,
    ADD COLUMN invoice_purpose VARCHAR(32) NOT NULL DEFAULT 'STANDARD' AFTER supersedes_invoice_id,
    ADD COLUMN cycle_idempotency_key VARCHAR(160) NULL AFTER invoice_purpose;
