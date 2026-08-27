-- LIVE common invoices created while SHADOW simulations were disabled could
-- freeze a valid recipient allocation without the immutable source generation.
-- Backfill only the exact latest active contractor attempt whose amount and
-- paid baseline still match the source. No recipient, amount or reserve changes.
CREATE TEMPORARY TABLE tmp_live_common_invoice_generations (
    invoice_id BIGINT NOT NULL PRIMARY KEY,
    allocation_id BIGINT NOT NULL UNIQUE,
    route_generation VARCHAR(36) NOT NULL
);

INSERT INTO tmp_live_common_invoice_generations (invoice_id, allocation_id, route_generation)
SELECT
    ci.invoice_id,
    cpa.id,
    UUID()
FROM common_invoices ci
JOIN contractor_payment_allocations cpa
  ON cpa.id = ci.contractor_allocation_id
WHERE ci.payment_route_type = 'MANUAL_MOBILE_BANK'
  AND ci.payment_route_manual_source = 'CONTRACTOR_PAYMENT_PROFILE'
  AND ci.payment_route_manual_type = 'MOBILE_BANK'
  AND ci.payment_route_amount_kopecks IS NOT NULL
  AND ci.payment_route_amount_kopecks > 0
  AND TRIM(COALESCE(ci.shadow_route_generation, '')) = ''
  AND cpa.mode = 'LIVE'
  AND cpa.source_type = 'COMMON_INVOICE'
  AND cpa.source_id = ci.invoice_id
  AND cpa.common_invoice_id = ci.invoice_id
  AND cpa.recipient_type IN ('SPECIALIST', 'MANAGER')
  AND cpa.status IN ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')
  AND cpa.recipient_profile_id IS NOT NULL
  AND cpa.amount_kopecks = ci.payment_route_amount_kopecks
  AND TRIM(COALESCE(cpa.source_generation_snapshot, '')) = ''
  AND ci.amount_kopecks = cpa.source_paid_baseline_kopecks + cpa.amount_kopecks
  AND ci.paid_kopecks >= cpa.source_paid_baseline_kopecks
  AND ci.paid_kopecks - cpa.source_paid_baseline_kopecks <= cpa.amount_kopecks
  AND GREATEST(0, cpa.confirmed_kopecks - cpa.returned_kopecks)
      <= ci.paid_kopecks - cpa.source_paid_baseline_kopecks
  AND ci.amount_kopecks - ci.paid_kopecks
      = cpa.amount_kopecks - (ci.paid_kopecks - cpa.source_paid_baseline_kopecks)
  AND cpa.id = (
      SELECT latest.id
      FROM contractor_payment_allocations latest
      WHERE latest.mode = 'LIVE'
        AND latest.source_type = 'COMMON_INVOICE'
        AND latest.source_id = ci.invoice_id
      ORDER BY latest.attempt_no DESC, latest.id DESC
      LIMIT 1
  );

UPDATE contractor_payment_allocations cpa
JOIN tmp_live_common_invoice_generations fix
  ON fix.allocation_id = cpa.id
SET cpa.source_generation_snapshot = fix.route_generation;

UPDATE common_invoices ci
JOIN tmp_live_common_invoice_generations fix
  ON fix.invoice_id = ci.invoice_id
JOIN contractor_payment_allocations cpa
  ON cpa.id = fix.allocation_id
SET ci.shadow_route_generation = fix.route_generation,
    ci.shadow_route_amount_kopecks = cpa.amount_kopecks,
    ci.shadow_route_prepared_at = COALESCE(
        ci.payment_route_selected_at,
        cpa.reserved_at,
        cpa.created_at,
        CURRENT_TIMESTAMP(6)
    );

DROP TEMPORARY TABLE tmp_live_common_invoice_generations;