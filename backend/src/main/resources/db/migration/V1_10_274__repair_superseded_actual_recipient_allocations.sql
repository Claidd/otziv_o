-- A final actual-recipient attribution is authoritative. Older reconciliation
-- code could nevertheless observe the confirmed payment link afterwards and
-- re-confirm its frozen original contractor allocation. Preserve the gross
-- confirmation for audit, but offset the invalid net exposure with one
-- idempotent RETURNED event and repair the current allocation snapshot.

CREATE TEMPORARY TABLE v274_superseded_actual_recipient_allocations (
    allocation_id BIGINT NOT NULL,
    status_before VARCHAR(32) NOT NULL,
    correction_kopecks BIGINT NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    PRIMARY KEY (allocation_id)
) ENGINE=InnoDB;

INSERT INTO v274_superseded_actual_recipient_allocations (
    allocation_id,
    status_before,
    correction_kopecks,
    effective_at
)
SELECT allocation.id,
       allocation.status,
       allocation.confirmed_kopecks - allocation.returned_kopecks,
       COALESCE(MAX(attribution.effective_at), allocation.confirmed_at,
                allocation.updated_at, CURRENT_TIMESTAMP(6))
FROM contractor_payment_allocations allocation
JOIN contractor_actual_payment_attributions attribution
  ON attribution.original_allocation_id = allocation.id
 AND attribution.source_kind = 'PAYMENT_LINK'
LEFT JOIN contractor_payment_allocations actual_allocation
  ON actual_allocation.mode = attribution.accounting_mode
 AND actual_allocation.source_type = 'ACTUAL_PAYMENT'
 AND actual_allocation.source_id = attribution.id
JOIN payment_links payment_link
  ON payment_link.id = attribution.source_id
WHERE allocation.source_type = 'PAYMENT_LINK'
  AND allocation.confirmed_kopecks > allocation.returned_kopecks
  AND payment_link.status IN (
      'CONFIRMED', 'TEST_CONFIRMED', 'AMOUNT_MISMATCH',
      'REFUNDED', 'REVERSED', 'PARTIAL_REFUNDED', 'PARTIAL_REVERSED'
  )
  AND (
      attribution.actual_recipient_type = 'OWNER'
      OR attribution.actual_cash_destination_kind = 'OWNER'
      OR actual_allocation.id IS NOT NULL
  )
GROUP BY allocation.id,
         allocation.status,
         allocation.confirmed_kopecks,
         allocation.returned_kopecks,
         allocation.confirmed_at,
         allocation.updated_at;

INSERT IGNORE INTO contractor_payment_allocation_events (
    allocation_id,
    event_type,
    amount_kopecks,
    status_before,
    status_after,
    effective_at,
    observed_at,
    reason,
    external_ref,
    actor
)
SELECT repair.allocation_id,
       'RETURNED',
       repair.correction_kopecks,
       repair.status_before,
       'RETURNED',
       repair.effective_at,
       CURRENT_TIMESTAMP(6),
       'Исходное подтверждение снято: деньги фактически получил другой получатель',
       'MIGRATION:V274:ACTUAL_RECIPIENT_SUPERSEDED',
       'migration:v274'
FROM v274_superseded_actual_recipient_allocations repair;

UPDATE contractor_payment_allocations allocation
JOIN v274_superseded_actual_recipient_allocations repair
  ON repair.allocation_id = allocation.id
SET allocation.returned_kopecks = allocation.confirmed_kopecks,
    allocation.needs_return_amount = FALSE,
    allocation.status = 'RETURNED',
    allocation.released_at = repair.effective_at,
    allocation.release_reason =
        'Исходное подтверждение снято: деньги фактически получил другой получатель',
    allocation.last_reconciled_at = CURRENT_TIMESTAMP(6),
    allocation.reconcile_claim_token = NULL,
    allocation.reconcile_lease_until = NULL,
    allocation.reconcile_next_retry_at = NULL,
    allocation.reconcile_last_error_code = NULL,
    allocation.row_version = allocation.row_version + 1,
    allocation.updated_at = CURRENT_TIMESTAMP(6);

DROP TEMPORARY TABLE v274_superseded_actual_recipient_allocations;
