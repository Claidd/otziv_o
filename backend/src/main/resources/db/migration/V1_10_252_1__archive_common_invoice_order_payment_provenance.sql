-- archive_common_invoice_orders was cloned before V215 introduced the live
-- source_payment_link_id column. V253 indexes that immutable provenance, so
-- restore schema parity before the evidence-boundary migration runs.
-- Historical rows intentionally stay NULL: their exact source cannot be
-- reconstructed safely.
ALTER TABLE archive_common_invoice_orders
    ADD COLUMN source_payment_link_id BIGINT NULL AFTER payment_method,
    ADD INDEX idx_archive_common_invoice_orders_source_payment_link
        (source_payment_link_id);
