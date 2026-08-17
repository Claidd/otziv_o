ALTER TABLE contractor_payment_profiles
    ADD COLUMN manual_task_commitment_kopecks BIGINT NOT NULL DEFAULT 0
        AFTER opening_balance_kopecks,
    ADD COLUMN manual_task_overrun_ack_kopecks BIGINT NOT NULL DEFAULT 0
        AFTER manual_task_commitment_kopecks,
    ADD CONSTRAINT chk_contractor_profile_manual_task_commitment
        CHECK (manual_task_commitment_kopecks >= 0),
    ADD CONSTRAINT chk_contractor_profile_manual_task_ack
        CHECK (manual_task_overrun_ack_kopecks >= 0);

ALTER TABLE manual_payment_tasks
    ADD COLUMN target_overrun_acknowledged_kopecks BIGINT NULL
        AFTER target_overrun_acknowledged_by,
    ADD COLUMN target_capacity_available_snapshot_kopecks BIGINT NULL
        AFTER target_overrun_acknowledged_kopecks,
    ADD CONSTRAINT chk_manual_task_overrun_ack_amount CHECK (
        target_overrun_acknowledged_kopecks IS NULL
        OR target_overrun_acknowledged_kopecks >= 0
    );

ALTER TABLE contractor_payment_allocations
    ADD COLUMN manual_payment_task_id BIGINT NULL AFTER common_invoice_id,
    ADD COLUMN task_capacity_position_before_kopecks BIGINT NULL
        AFTER available_before_kopecks,
    ADD COLUMN task_capacity_commitment_before_kopecks BIGINT NULL
        AFTER task_capacity_position_before_kopecks,
    ADD COLUMN task_capacity_projected_overrun_kopecks BIGINT NULL
        AFTER task_capacity_commitment_before_kopecks,
    ADD COLUMN task_capacity_acknowledged_kopecks BIGINT NULL
        AFTER task_capacity_projected_overrun_kopecks,
    ADD COLUMN task_capacity_acknowledged_at DATETIME(6) NULL
        AFTER task_capacity_acknowledged_kopecks,
    ADD COLUMN task_capacity_acknowledged_by VARCHAR(160) NULL
        AFTER task_capacity_acknowledged_at,
    ADD CONSTRAINT fk_contractor_allocation_manual_task
        FOREIGN KEY (manual_payment_task_id) REFERENCES manual_payment_tasks (id),
    ADD INDEX idx_contractor_allocation_manual_task (manual_payment_task_id, id),
    ADD CONSTRAINT chk_contractor_allocation_task_capacity_overrun CHECK (
        task_capacity_projected_overrun_kopecks IS NULL
        OR task_capacity_projected_overrun_kopecks >= 0
    );

-- Link the currently frozen task attempts. Historical attempts stay immutable
-- and are not treated as backing a live task reservation.
UPDATE contractor_payment_allocations allocation
JOIN payment_links link
  ON allocation.source_type = 'PAYMENT_LINK'
 AND allocation.source_id = link.id
 AND allocation.id = link.contractor_allocation_id
SET allocation.manual_payment_task_id = link.manual_task_id
WHERE link.manual_task_id IS NOT NULL;

UPDATE contractor_payment_allocations allocation
JOIN common_invoices invoice
  ON allocation.source_type = 'COMMON_INVOICE'
 AND allocation.source_id = invoice.invoice_id
 AND allocation.id = invoice.contractor_allocation_id
SET allocation.manual_payment_task_id = invoice.payment_route_manual_task_id
WHERE invoice.payment_route_manual_task_id IS NOT NULL;

-- Split/redirect confirmations use an ACTUAL_PAYMENT allocation (or reuse the
-- exact original allocation when the economic recipient did not change).
-- Tag both shapes so exposure survives the SHADOW -> LIVE phase boundary.
DELIMITER $$

DROP PROCEDURE IF EXISTS assert_manual_task_allocation_provenance $$
CREATE PROCEDURE assert_manual_task_allocation_provenance()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM contractor_payment_allocations allocation
        JOIN contractor_actual_payment_attributions attribution
          ON ((allocation.source_type = 'ACTUAL_PAYMENT'
               AND allocation.source_id = attribution.id)
              OR allocation.id = attribution.original_allocation_id)
        JOIN manual_payment_tasks task
          ON task.id = attribution.actual_manual_payment_task_id
        WHERE attribution.actual_cash_destination_kind = 'MANUAL_PAYMENT_TASK'
          AND attribution.actual_manual_payment_task_target_kind
                IN ('SPECIALIST', 'MANAGER')
          AND task.accounting_target_kind
                = attribution.actual_manual_payment_task_target_kind
          AND task.accounting_target_profile_id
                = attribution.actual_recipient_profile_id
          AND allocation.recipient_profile_id
                = task.accounting_target_profile_id
          AND allocation.mode = attribution.accounting_mode
          AND allocation.manual_payment_task_id IS NOT NULL
          AND allocation.manual_payment_task_id <> task.id
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V254 conflicting manual task allocation provenance';
    END IF;
END $$

DELIMITER ;

CALL assert_manual_task_allocation_provenance();
DROP PROCEDURE assert_manual_task_allocation_provenance;

UPDATE contractor_payment_allocations allocation
JOIN contractor_actual_payment_attributions attribution
  ON (
       (allocation.source_type = 'ACTUAL_PAYMENT'
        AND allocation.source_id = attribution.id)
       OR allocation.id = attribution.original_allocation_id
     )
JOIN manual_payment_tasks task
  ON task.id = attribution.actual_manual_payment_task_id
SET allocation.manual_payment_task_id = task.id
WHERE attribution.actual_cash_destination_kind = 'MANUAL_PAYMENT_TASK'
  AND attribution.actual_manual_payment_task_target_kind IN ('SPECIALIST', 'MANAGER')
  AND task.accounting_target_kind = attribution.actual_manual_payment_task_target_kind
  AND task.accounting_target_profile_id = attribution.actual_recipient_profile_id
  AND allocation.recipient_profile_id = task.accounting_target_profile_id
  AND allocation.mode = attribution.accounting_mode
  AND (allocation.manual_payment_task_id IS NULL
       OR allocation.manual_payment_task_id = task.id);

-- Old acknowledgements did not persist an amount and therefore cannot safely
-- authorize a future overrun. They remain visible for audit, but amount-based
-- authorization starts fail-closed at zero.
UPDATE contractor_payment_profiles profile
LEFT JOIN (
    SELECT task.accounting_target_profile_id AS profile_id,
           SUM(CASE
               WHEN task.status IN ('COMPLETED', 'CANCELED')
               THEN GREATEST(0, COALESCE(balance.unbacked_confirmed_kopecks, 0))
               ELSE GREATEST(
                   0,
                   task.target_amount_kopecks
                       - GREATEST(0, COALESCE(balance.confirmed_kopecks, 0))
                       - GREATEST(0, COALESCE(balance.pending_kopecks, 0))
               ) + GREATEST(
                   0,
                   COALESCE(balance.unbacked_confirmed_kopecks, 0)
               )
           END) AS commitment_kopecks
    FROM manual_payment_tasks task
    LEFT JOIN (
        SELECT source.task_id,
               SUM(source.confirmed_kopecks) AS confirmed_kopecks,
               SUM(source.pending_kopecks) AS pending_kopecks,
               SUM(GREATEST(
                   0,
                   source.confirmed_kopecks - GREATEST(
                       0,
                       source.backed_confirmed_kopecks
                           - source.negative_confirmed_kopecks
                   )
               )) AS unbacked_confirmed_kopecks
        FROM (
            SELECT entry.task_id,
                   entry.source_kind,
                   entry.source_id,
                   entry.source_generation,
                   SUM(entry.confirmed_delta_kopecks) AS confirmed_kopecks,
                   SUM(entry.reserved_delta_kopecks) AS pending_kopecks,
                   SUM(CASE
                       WHEN entry.verified = TRUE
                        AND entry.event_type = 'CONFIRMED_TO_TASK'
                        AND entry.confirmed_delta_kopecks > 0
                       THEN entry.confirmed_delta_kopecks
                       ELSE 0
                   END) AS backed_confirmed_kopecks,
                   SUM(CASE
                       WHEN entry.confirmed_delta_kopecks < 0
                       THEN -entry.confirmed_delta_kopecks
                       ELSE 0
                   END) AS negative_confirmed_kopecks
            FROM manual_payment_task_ledger_entries entry
            GROUP BY entry.task_id, entry.source_kind,
                     entry.source_id, entry.source_generation
        ) source
        GROUP BY source.task_id
    ) balance ON balance.task_id = task.id
    WHERE task.accounting_target_kind IN ('SPECIALIST', 'MANAGER')
      AND task.accounting_target_profile_id IS NOT NULL
      AND (
          task.status IN ('ACTIVE', 'PAUSED', 'NEEDS_ATTENTION')
          OR (
              task.status IN ('COMPLETED', 'CANCELED')
              AND GREATEST(0, COALESCE(balance.unbacked_confirmed_kopecks, 0)) > 0
          )
      )
    GROUP BY task.accounting_target_profile_id
) totals ON totals.profile_id = profile.id
SET profile.manual_task_commitment_kopecks = COALESCE(totals.commitment_kopecks, 0),
    profile.manual_task_overrun_ack_kopecks = 0;
