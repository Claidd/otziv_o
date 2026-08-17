ALTER TABLE contractor_actual_payment_attributions
    ADD COLUMN original_cash_destination_kind VARCHAR(32) NULL AFTER accounting_mode,
    ADD COLUMN original_manual_payment_task_id BIGINT NULL AFTER original_recipient_name_snapshot,
    ADD COLUMN original_manual_payment_task_generation BIGINT NULL AFTER original_manual_payment_task_id,
    ADD COLUMN original_manual_payment_task_target_kind VARCHAR(32) NULL AFTER original_manual_payment_task_generation,
    ADD COLUMN actual_cash_destination_kind VARCHAR(32) NULL AFTER original_manual_payment_task_target_kind,
    ADD COLUMN actual_manual_payment_task_id BIGINT NULL AFTER actual_recipient_name_snapshot,
    ADD COLUMN actual_manual_payment_task_generation BIGINT NULL AFTER actual_manual_payment_task_id,
    ADD COLUMN actual_manual_payment_task_target_kind VARCHAR(32) NULL AFTER actual_manual_payment_task_generation,
    ADD INDEX idx_actual_payment_original_task (original_manual_payment_task_id, original_manual_payment_task_generation),
    ADD INDEX idx_actual_payment_actual_task (actual_manual_payment_task_id, actual_manual_payment_task_generation);

UPDATE contractor_actual_payment_attributions
SET original_cash_destination_kind = CASE
        WHEN original_recipient_type = 'OWNER' THEN 'OWNER'
        ELSE 'CONTRACTOR_PROFILE'
    END,
    actual_cash_destination_kind = CASE
        WHEN actual_recipient_type = 'OWNER' THEN 'OWNER'
        ELSE 'CONTRACTOR_PROFILE'
    END
WHERE original_cash_destination_kind IS NULL
   OR actual_cash_destination_kind IS NULL;

ALTER TABLE contractor_actual_payment_attributions
    MODIFY COLUMN original_cash_destination_kind VARCHAR(32) NOT NULL,
    MODIFY COLUMN actual_cash_destination_kind VARCHAR(32) NOT NULL,
    MODIFY COLUMN original_recipient_type VARCHAR(24) NULL,
    MODIFY COLUMN actual_recipient_type VARCHAR(24) NULL;

ALTER TABLE payment_links
    ADD COLUMN manual_task_source_generation VARCHAR(36) NULL AFTER manual_task_id,
    ADD COLUMN manual_task_generation BIGINT NULL AFTER manual_task_source_generation,
    ADD COLUMN manual_actual_original_cash_destination_kind VARCHAR(32) NULL AFTER manual_actual_accounting_mode,
    ADD COLUMN manual_actual_original_task_id BIGINT NULL AFTER manual_actual_original_recipient_name_snapshot,
    ADD COLUMN manual_actual_original_task_generation BIGINT NULL AFTER manual_actual_original_task_id,
    ADD COLUMN manual_actual_original_task_target_kind VARCHAR(32) NULL AFTER manual_actual_original_task_generation,
    ADD COLUMN manual_actual_cash_destination_kind VARCHAR(32) NULL AFTER manual_actual_original_task_target_kind,
    ADD COLUMN manual_actual_task_id BIGINT NULL AFTER manual_actual_recipient_name_snapshot,
    ADD COLUMN manual_actual_task_generation BIGINT NULL AFTER manual_actual_task_id,
    ADD COLUMN manual_actual_task_target_kind VARCHAR(32) NULL AFTER manual_actual_task_generation,
    ADD INDEX idx_payment_links_manual_task_source_generation (manual_task_source_generation),
    ADD INDEX idx_payment_links_manual_actual_task (manual_actual_task_id, manual_actual_task_generation);

ALTER TABLE common_invoices
    ADD COLUMN payment_route_manual_task_source_generation VARCHAR(36) NULL AFTER payment_route_manual_task_id,
    ADD COLUMN payment_route_manual_task_generation BIGINT NULL AFTER payment_route_manual_task_source_generation,
    ADD COLUMN payment_route_manual_task_accounting_mode VARCHAR(16) NULL AFTER payment_route_manual_task_generation,
    ADD INDEX idx_common_invoices_manual_task_source_generation (payment_route_manual_task_source_generation);

ALTER TABLE archive_common_invoices
    ADD COLUMN payment_route_manual_task_source_generation VARCHAR(36) NULL AFTER payment_route_manual_task_id,
    ADD COLUMN payment_route_manual_task_generation BIGINT NULL AFTER payment_route_manual_task_source_generation,
    ADD COLUMN payment_route_manual_task_accounting_mode VARCHAR(16) NULL AFTER payment_route_manual_task_generation,
    ADD CONSTRAINT ck_archive_common_manual_task_accounting_mode CHECK (
        payment_route_manual_task_accounting_mode IS NULL
        OR payment_route_manual_task_accounting_mode IN ('SHADOW', 'LIVE')
    );

ALTER TABLE common_invoices
    ADD CONSTRAINT ck_common_manual_task_accounting_mode CHECK (
        payment_route_manual_task_accounting_mode IS NULL
        OR payment_route_manual_task_accounting_mode IN ('SHADOW', 'LIVE')
    );

-- V250 may have frozen an ordinary manual-payment intent before typed cash
-- destinations existed. Preserve that immutable intent instead of making the
-- already-cancelled bank flow impossible to finish after this migration.
UPDATE payment_links
SET manual_actual_original_cash_destination_kind = CASE
        WHEN manual_actual_original_recipient_type = 'OWNER' THEN 'OWNER'
        WHEN manual_actual_original_recipient_type IN ('SPECIALIST', 'MANAGER')
            THEN 'CONTRACTOR_PROFILE'
        ELSE manual_actual_original_cash_destination_kind
    END,
    manual_actual_cash_destination_kind = CASE
        WHEN manual_actual_recipient_type = 'OWNER' THEN 'OWNER'
        WHEN manual_actual_recipient_type IN ('SPECIALIST', 'MANAGER')
            THEN 'CONTRACTOR_PROFILE'
        ELSE manual_actual_cash_destination_kind
    END
WHERE manual_actual_recipient_frozen_at IS NOT NULL
  AND (manual_actual_original_cash_destination_kind IS NULL
       OR manual_actual_cash_destination_kind IS NULL);

-- V251 quarantines pre-existing pending and confirmed task routes under these
-- exact source generations. Confirmed rows need the same binding so an
-- authoritative full reversal can debit its exact unverified source baseline.
-- Keep source-facing requisites on the source rows; the task ledger deliberately does not duplicate legacy PII.
UPDATE payment_links link
JOIN manual_payment_tasks task ON task.id = link.manual_task_id
SET link.manual_task_source_generation = CONCAT('LEGACY-', link.id),
    link.manual_task_generation = task.generation
WHERE link.manual_source = 'MANUAL_TASK'
  AND link.payment_method IN ('MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK')
  AND link.status IN ('WAITING_MANUAL_PAYMENT', 'MANUAL_REPORTED', 'CONFIRMED')
  AND link.manual_task_source_generation IS NULL;

UPDATE common_invoices invoice
JOIN manual_payment_tasks task ON task.id = invoice.payment_route_manual_task_id
SET invoice.payment_route_manual_task_source_generation = CONCAT('LEGACY-', invoice.invoice_id),
    invoice.payment_route_manual_task_generation = task.generation
WHERE invoice.payment_route_manual_source = 'MANUAL_TASK'
  AND invoice.payment_route_amount_kopecks > 0
  AND invoice.payment_route_manual_task_source_generation IS NULL;

-- Only an immutable contractor allocation can prove the historic accounting
-- mode. OWNER/EXTERNAL legacy routes without that evidence remain NULL and
-- deliberately require reconciliation; newly issued routes persist mode at
-- reservation time.
UPDATE common_invoices invoice
JOIN contractor_payment_allocations allocation
  ON allocation.id = invoice.contractor_allocation_id
SET invoice.payment_route_manual_task_accounting_mode = allocation.mode
WHERE invoice.payment_route_manual_source = 'MANUAL_TASK'
  AND invoice.payment_route_manual_task_accounting_mode IS NULL;

UPDATE archive_common_invoices invoice
JOIN contractor_payment_allocations allocation
  ON allocation.id = invoice.contractor_allocation_id
SET invoice.payment_route_manual_task_accounting_mode = allocation.mode
WHERE invoice.payment_route_manual_source = 'MANUAL_TASK'
  AND invoice.payment_route_manual_task_accounting_mode IS NULL;
