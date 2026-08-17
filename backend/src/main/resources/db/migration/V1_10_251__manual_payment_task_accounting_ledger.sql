ALTER TABLE manual_payment_tasks
    ADD COLUMN accounting_target_kind VARCHAR(32) NOT NULL DEFAULT 'UNRESOLVED'
        AFTER payment_profile_id,
    ADD COLUMN accounting_target_profile_id BIGINT NULL
        AFTER accounting_target_kind,
    ADD COLUMN generation BIGINT NOT NULL DEFAULT 1
        AFTER accounting_target_profile_id,
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0
        AFTER generation,
    ADD COLUMN needs_reconciliation BOOLEAN NOT NULL DEFAULT FALSE
        AFTER row_version,
    ADD COLUMN target_overrun_acknowledged_at DATETIME(6) NULL
        AFTER needs_reconciliation,
    ADD COLUMN target_overrun_acknowledged_by VARCHAR(160) NULL
        AFTER target_overrun_acknowledged_at,
    ADD CONSTRAINT fk_manual_payment_tasks_accounting_target_profile
        FOREIGN KEY (accounting_target_profile_id) REFERENCES contractor_payment_profiles (id),
    ADD INDEX idx_manual_payment_tasks_accounting_target
        (accounting_target_kind, accounting_target_profile_id),
    ADD CONSTRAINT chk_manual_payment_tasks_accounting_target CHECK (
        (accounting_target_kind IN ('SPECIALIST', 'MANAGER')
            AND accounting_target_profile_id IS NOT NULL)
        OR
        (accounting_target_kind IN ('UNRESOLVED', 'EXTERNAL_TASK', 'OWNER')
            AND accounting_target_profile_id IS NULL)
    );

-- Every pre-V251 task requires a deliberate one-time recipient binding. Bank
-- display fields are intentionally not used to infer this value.
UPDATE manual_payment_tasks
SET accounting_target_kind = 'UNRESOLVED',
    accounting_target_profile_id = NULL,
    needs_reconciliation = TRUE;

CREATE TABLE manual_payment_task_ledger_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    task_generation BIGINT NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    source_generation VARCHAR(36) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    operation_key VARCHAR(160) NOT NULL,
    operation_sequence INT NOT NULL,
    reservation_key VARCHAR(160) NULL,
    reserved_delta_kopecks BIGINT NOT NULL,
    confirmed_delta_kopecks BIGINT NOT NULL,
    redirected_amount_kopecks BIGINT NOT NULL DEFAULT 0,
    accounting_target_kind VARCHAR(32) NOT NULL,
    accounting_target_profile_id BIGINT NULL,
    accounting_target_label_snapshot TEXT NULL,
    manual_payment_type VARCHAR(32) NOT NULL,
    manual_phone_snapshot TEXT NULL,
    bank_recipient_name_snapshot TEXT NULL,
    manual_payment_url_snapshot TEXT NULL,
    manual_payment_button_snapshot VARCHAR(80) NULL,
    selected_recipient_key VARCHAR(160) NULL,
    target_overrun_acknowledged_at DATETIME(6) NULL,
    target_overrun_acknowledged_by VARCHAR(160) NULL,
    verified BOOLEAN NOT NULL,
    actor VARCHAR(160) NOT NULL,
    reason VARCHAR(500) NULL,
    correction_of_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_manual_task_ledger_task
        FOREIGN KEY (task_id) REFERENCES manual_payment_tasks (id),
    CONSTRAINT fk_manual_task_ledger_target_profile
        FOREIGN KEY (accounting_target_profile_id) REFERENCES contractor_payment_profiles (id),
    CONSTRAINT fk_manual_task_ledger_correction
        FOREIGN KEY (correction_of_id) REFERENCES manual_payment_task_ledger_entries (id),
    CONSTRAINT uk_manual_task_ledger_operation_sequence
        UNIQUE (operation_key, operation_sequence),
    CONSTRAINT uk_manual_task_ledger_reservation_key UNIQUE (reservation_key),
    INDEX idx_manual_task_ledger_task_created (task_id, created_at, id),
    INDEX idx_manual_task_ledger_source
        (source_kind, source_id, source_generation, id),
    INDEX idx_manual_task_ledger_target_profile
        (accounting_target_profile_id, event_type, id),
    INDEX idx_manual_task_ledger_correction (correction_of_id),
    CONSTRAINT chk_manual_task_ledger_source CHECK (
        source_kind IN ('PAYMENT_LINK', 'COMMON_INVOICE', 'LEGACY_TASK_BASELINE')
    ),
    CONSTRAINT chk_manual_task_ledger_target CHECK (
        (accounting_target_kind IN ('SPECIALIST', 'MANAGER')
            AND accounting_target_profile_id IS NOT NULL)
        OR
        (accounting_target_kind IN ('UNRESOLVED', 'EXTERNAL_TASK', 'OWNER')
            AND accounting_target_profile_id IS NULL)
    )
) ENGINE=InnoDB;

-- Preserve every pre-V251 confirmed source as separate unverified opening
-- evidence. The recipient remains unresolved, but an authoritative provider
-- reversal can now debit the exact task/source without guessing or
-- double-counting another payment belonging to the same task.
INSERT INTO manual_payment_task_ledger_entries (
    task_id, task_generation, source_kind, source_id, source_generation,
    event_type, operation_key, operation_sequence, reservation_key,
    reserved_delta_kopecks, confirmed_delta_kopecks, redirected_amount_kopecks,
    accounting_target_kind, accounting_target_profile_id,
    accounting_target_label_snapshot, manual_payment_type,
    manual_phone_snapshot, bank_recipient_name_snapshot,
    manual_payment_url_snapshot, manual_payment_button_snapshot,
    selected_recipient_key, verified, actor, reason, created_at
)
SELECT task.id,
       task.generation,
       confirmed.source_kind,
       confirmed.source_id,
       CONCAT('LEGACY-', confirmed.source_id),
       'LEGACY_BASELINE',
       CONCAT('V251:BASELINE:', confirmed.source_kind, ':', confirmed.source_id),
       0,
       NULL,
       0,
       confirmed.confirmed_kopecks,
       0,
       'UNRESOLVED',
       NULL,
       NULL,
       COALESCE(confirmed.manual_payment_type, task.manual_payment_type),
       NULL,
       NULL,
       NULL,
       COALESCE(confirmed.manual_payment_button, task.manual_payment_button_label),
       NULL,
       FALSE,
       'flyway-v251',
       'Непроверенная подтвержденная оплата до типизированного учета',
       CURRENT_TIMESTAMP(6)
FROM manual_payment_tasks task
JOIN (
        SELECT link.manual_task_id AS task_id,
               'PAYMENT_LINK' AS source_kind,
               link.id AS source_id,
               COALESCE(link.confirmed_amount_kopecks,
                        link.reserved_amount_kopecks,
                        link.amount_kopecks) AS confirmed_kopecks,
               link.manual_payment_type AS manual_payment_type,
               link.manual_payment_button_label AS manual_payment_button
        FROM payment_links link
        WHERE link.manual_task_id IS NOT NULL
          AND link.manual_source = 'MANUAL_TASK'
          AND link.payment_method IN ('MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK')
          AND link.status = 'CONFIRMED'

        UNION ALL

        SELECT invoice.payment_route_manual_task_id AS task_id,
               'COMMON_INVOICE' AS source_kind,
               invoice.invoice_id AS source_id,
               invoice.payment_route_amount_kopecks AS confirmed_kopecks,
               invoice.payment_route_manual_type AS manual_payment_type,
               invoice.payment_route_manual_button AS manual_payment_button
        FROM common_invoices invoice
        WHERE invoice.payment_route_manual_task_id IS NOT NULL
          AND invoice.payment_route_manual_source = 'MANUAL_TASK'
          AND invoice.payment_route_selected_at IS NOT NULL
          AND invoice.payment_route_amount_kopecks > 0
          AND invoice.paid_kopecks >= invoice.amount_kopecks

        UNION ALL

        SELECT archived_link.manual_task_id AS task_id,
               'PAYMENT_LINK' AS source_kind,
               archived_link.id AS source_id,
               COALESCE(archived_link.confirmed_amount_kopecks,
                        archived_link.reserved_amount_kopecks,
                        archived_link.amount_kopecks) AS confirmed_kopecks,
               NULL AS manual_payment_type,
               NULL AS manual_payment_button
        FROM archive_payment_links archived_link
        WHERE archived_link.manual_task_id IS NOT NULL
          AND archived_link.manual_source = 'MANUAL_TASK'
          AND archived_link.payment_method IN ('MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK')
          AND archived_link.status = 'CONFIRMED'
          AND NOT EXISTS (
              SELECT 1 FROM payment_links live_link WHERE live_link.id = archived_link.id
          )

        UNION ALL

        SELECT archived_invoice.payment_route_manual_task_id AS task_id,
               'COMMON_INVOICE' AS source_kind,
               archived_invoice.invoice_id AS source_id,
               archived_invoice.payment_route_amount_kopecks AS confirmed_kopecks,
               NULL AS manual_payment_type,
               NULL AS manual_payment_button
        FROM archive_common_invoices archived_invoice
        WHERE archived_invoice.payment_route_manual_task_id IS NOT NULL
          AND archived_invoice.payment_route_manual_source = 'MANUAL_TASK'
          AND archived_invoice.payment_route_selected_at IS NOT NULL
          AND archived_invoice.payment_route_amount_kopecks > 0
          AND archived_invoice.paid_kopecks >= archived_invoice.amount_kopecks
          AND archived_invoice.restored_at IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM common_invoices live_invoice
              WHERE live_invoice.invoice_id = archived_invoice.invoice_id
          )
    ) confirmed ON confirmed.task_id = task.id
WHERE confirmed.confirmed_kopecks > 0;

-- Each live standalone pending source remains independently releasable and
-- settleable. Its recipient stays unresolved until a human binds the task.
INSERT INTO manual_payment_task_ledger_entries (
    task_id, task_generation, source_kind, source_id, source_generation,
    event_type, operation_key, operation_sequence, reservation_key,
    reserved_delta_kopecks, confirmed_delta_kopecks, redirected_amount_kopecks,
    accounting_target_kind, accounting_target_profile_id,
    accounting_target_label_snapshot, manual_payment_type,
    manual_phone_snapshot, bank_recipient_name_snapshot,
    manual_payment_url_snapshot, manual_payment_button_snapshot,
    selected_recipient_key, verified, actor, reason, created_at
)
SELECT task.id,
       task.generation,
       'PAYMENT_LINK',
       link.id,
       CONCAT('LEGACY-', link.id),
       'RESERVED',
       CONCAT('V251:RESERVE:PAYMENT_LINK:', link.id),
       0,
       CONCAT('PAYMENT_LINK:', link.id, ':LEGACY-', link.id),
       COALESCE(link.confirmed_amount_kopecks,
                link.reserved_amount_kopecks,
                link.amount_kopecks),
       0,
       0,
       'UNRESOLVED',
       NULL,
       NULL,
       COALESCE(link.manual_payment_type, task.manual_payment_type),
       NULL,
       NULL,
       NULL,
       COALESCE(link.manual_payment_button_label, task.manual_payment_button_label),
       NULL,
       FALSE,
       'flyway-v251',
       'Перенос действующего резерва без предположения о получателе',
       CURRENT_TIMESTAMP(6)
FROM payment_links link
JOIN manual_payment_tasks task ON task.id = link.manual_task_id
WHERE link.manual_source = 'MANUAL_TASK'
  AND link.payment_method IN ('MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK')
  AND link.status IN ('WAITING_MANUAL_PAYMENT', 'MANUAL_REPORTED')
  AND COALESCE(link.confirmed_amount_kopecks,
               link.reserved_amount_kopecks,
               link.amount_kopecks) > 0;

-- Expired rows must still have a complete source history so the ordinary
-- runtime expiry path can replay safely, but they must not inflate occupied
-- task totals between Flyway and the first scheduler pass.
INSERT INTO manual_payment_task_ledger_entries (
    task_id, task_generation, source_kind, source_id, source_generation,
    event_type, operation_key, operation_sequence, reservation_key,
    reserved_delta_kopecks, confirmed_delta_kopecks, redirected_amount_kopecks,
    accounting_target_kind, accounting_target_profile_id,
    accounting_target_label_snapshot, manual_payment_type,
    manual_phone_snapshot, bank_recipient_name_snapshot,
    manual_payment_url_snapshot, manual_payment_button_snapshot,
    selected_recipient_key, verified, actor, reason, created_at
)
SELECT task.id,
       task.generation,
       'PAYMENT_LINK',
       link.id,
       CONCAT('LEGACY-', link.id),
       'RELEASED',
       CONCAT('TASK:RELEASE:PAYMENT_LINK:', link.id, ':LEGACY-', link.id),
       0,
       NULL,
       -COALESCE(link.confirmed_amount_kopecks,
                 link.reserved_amount_kopecks,
                 link.amount_kopecks),
       0,
       0,
       'UNRESOLVED',
       NULL,
       NULL,
       COALESCE(link.manual_payment_type, task.manual_payment_type),
       NULL,
       NULL,
       NULL,
       COALESCE(link.manual_payment_button_label, task.manual_payment_button_label),
       NULL,
       TRUE,
       'system:payment-routing',
       'Срок действия ручной платежной ссылки истек',
       CURRENT_TIMESTAMP(6)
FROM payment_links link
JOIN manual_payment_tasks task ON task.id = link.manual_task_id
WHERE link.manual_source = 'MANUAL_TASK'
  AND link.payment_method IN ('MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK')
  AND link.status IN ('WAITING_MANUAL_PAYMENT', 'MANUAL_REPORTED')
  AND link.expires_at <= CURRENT_TIMESTAMP(6)
  AND COALESCE(link.confirmed_amount_kopecks,
               link.reserved_amount_kopecks,
               link.amount_kopecks) > 0;

-- Common invoices use their frozen route amount, matching the old task metric.
INSERT INTO manual_payment_task_ledger_entries (
    task_id, task_generation, source_kind, source_id, source_generation,
    event_type, operation_key, operation_sequence, reservation_key,
    reserved_delta_kopecks, confirmed_delta_kopecks, redirected_amount_kopecks,
    accounting_target_kind, accounting_target_profile_id,
    accounting_target_label_snapshot, manual_payment_type,
    manual_phone_snapshot, bank_recipient_name_snapshot,
    manual_payment_url_snapshot, manual_payment_button_snapshot,
    selected_recipient_key, verified, actor, reason, created_at
)
SELECT task.id,
       task.generation,
       'COMMON_INVOICE',
       invoice.invoice_id,
       CONCAT('LEGACY-', invoice.invoice_id),
       'RESERVED',
       CONCAT('V251:RESERVE:COMMON_INVOICE:', invoice.invoice_id),
       0,
       CONCAT('COMMON_INVOICE:', invoice.invoice_id, ':LEGACY-', invoice.invoice_id),
       invoice.payment_route_amount_kopecks,
       0,
       0,
       'UNRESOLVED',
       NULL,
       NULL,
       COALESCE(invoice.payment_route_manual_type, task.manual_payment_type),
       NULL,
       NULL,
       NULL,
       COALESCE(invoice.payment_route_manual_button, task.manual_payment_button_label),
       NULL,
       FALSE,
       'flyway-v251',
       'Перенос действующего общего резерва без предположения о получателе',
       CURRENT_TIMESTAMP(6)
FROM common_invoices invoice
JOIN manual_payment_tasks task ON task.id = invoice.payment_route_manual_task_id
WHERE invoice.payment_route_manual_source = 'MANUAL_TASK'
  AND invoice.payment_route_selected_at IS NOT NULL
  AND invoice.payment_route_amount_kopecks > 0
  AND invoice.status IN (
      'COLLECTING', 'READY', 'INVOICED', 'REMINDER',
      'PARTIALLY_PAID', 'NEEDS_ATTENTION'
  )
  AND invoice.paid_kopecks < invoice.amount_kopecks
  AND invoice.payment_route_amount_kopecks <= invoice.amount_kopecks
  AND invoice.paid_kopecks >= GREATEST(
      0, invoice.amount_kopecks - invoice.payment_route_amount_kopecks
  );

-- A partially-paid invoice can legitimately retain the entire frozen route:
-- paid_kopecks may include evidence that predates the task route. Preserve the
-- conservative exposure, but force explicit reconciliation when paid evidence
-- exceeds that baseline. Impossible route arithmetic is quarantined without
-- inventing a task reserve.
UPDATE common_invoices invoice
JOIN manual_payment_tasks task ON task.id = invoice.payment_route_manual_task_id
SET invoice.status = 'NEEDS_ATTENTION',
    task.status = 'NEEDS_ATTENTION',
    task.needs_reconciliation = TRUE
WHERE invoice.payment_route_manual_source = 'MANUAL_TASK'
  AND invoice.payment_route_selected_at IS NOT NULL
  AND invoice.payment_route_amount_kopecks > 0
  AND invoice.paid_kopecks < invoice.amount_kopecks
  AND (
      invoice.payment_route_amount_kopecks > invoice.amount_kopecks
      OR invoice.paid_kopecks < GREATEST(
          0, invoice.amount_kopecks - invoice.payment_route_amount_kopecks
      )
      OR invoice.paid_kopecks > GREATEST(
          0, invoice.amount_kopecks - invoice.payment_route_amount_kopecks
      )
  );

-- A closed task with a still-positive exact source reserve is not terminal.
-- Reopen it for attention so the administrator can bind or release that
-- source instead of leaving hidden exposure under COMPLETED/CANCELED.
UPDATE manual_payment_tasks task
SET task.status = 'NEEDS_ATTENTION',
    task.needs_reconciliation = TRUE
WHERE task.status IN ('COMPLETED', 'CANCELED')
  AND EXISTS (
      SELECT 1
      FROM manual_payment_task_ledger_entries source_entry
      WHERE source_entry.task_id = task.id
        AND source_entry.source_kind IN ('PAYMENT_LINK', 'COMMON_INVOICE')
      GROUP BY source_entry.source_kind,
               source_entry.source_id,
               source_entry.source_generation
      HAVING SUM(source_entry.reserved_delta_kopecks) > 0
  );
