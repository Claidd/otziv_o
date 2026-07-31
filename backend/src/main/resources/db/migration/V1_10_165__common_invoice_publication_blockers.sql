ALTER TABLE common_invoice_orders
    ADD COLUMN publication_blocker_since DATETIME(6) NULL,
    ADD COLUMN invoice_linked_at DATETIME(6) NULL;

UPDATE common_invoice_orders
SET invoice_linked_at = created_at
WHERE invoice_linked_at IS NULL;

CREATE INDEX idx_common_invoice_publication_blocker
    ON common_invoice_orders (publication_blocker_since, invoice_id);
