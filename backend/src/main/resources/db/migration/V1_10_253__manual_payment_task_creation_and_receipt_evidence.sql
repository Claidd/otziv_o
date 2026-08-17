-- V250 had no item-level evidence boundary. Refuse to guess if a previous
-- deployment already accepted a typed common receipt; those sources require
-- an explicit operator reconciliation before this migration can be applied.
DROP TEMPORARY TABLE IF EXISTS v253_common_attribution_preflight;

CREATE TEMPORARY TABLE v253_common_attribution_preflight (
    existing_count BIGINT NOT NULL,
    CONSTRAINT chk_v253_no_unmapped_common_attribution CHECK (existing_count = 0)
);

INSERT INTO v253_common_attribution_preflight (existing_count)
SELECT COUNT(*)
FROM contractor_actual_payment_attributions
WHERE source_kind = 'COMMON_INVOICE';

DROP TEMPORARY TABLE v253_common_attribution_preflight;

CREATE TABLE manual_payment_task_creation_requests (
    operation_key VARCHAR(160) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    task_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (operation_key),
    INDEX idx_manual_task_creation_task (task_id),
    CONSTRAINT chk_manual_task_creation_payload_hash
        CHECK (CHAR_LENGTH(payload_hash) = 64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Exact item-level boundary between old untyped common-invoice receipts and
-- V250+ typed actual-recipient batches. Archive is kept in schema parity.
ALTER TABLE common_invoice_orders
    ADD COLUMN actual_payment_evidence_reference VARCHAR(160) NULL
        AFTER manual_payment_receipt_url,
    ADD INDEX idx_common_invoice_order_actual_evidence
        (actual_payment_evidence_reference, invoice_id),
    ADD INDEX idx_common_invoice_order_manual_month
        (paid, paid_at, source_payment_link_id, invoice_id);

ALTER TABLE archive_common_invoice_orders
    ADD COLUMN actual_payment_evidence_reference VARCHAR(160) NULL
        AFTER manual_payment_receipt_url,
    ADD INDEX idx_archive_common_invoice_order_actual_evidence
        (actual_payment_evidence_reference, invoice_id),
    ADD INDEX idx_archive_common_invoice_order_manual_month
        (paid, paid_at, source_payment_link_id, invoice_id);
