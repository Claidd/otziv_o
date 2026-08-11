-- Early retirement uses EXPIRED even when the original payment deadline is
-- still in the future. Before V232, shadow OWNER_FALLBACK rows copied that
-- future deadline into their zero-value expiry event. No contractor balance
-- was affected; repair only the observed non-financial shadow-owner shape.
UPDATE contractor_payment_allocations allocation
JOIN contractor_payment_allocation_events event_row
  ON event_row.allocation_id = allocation.id
SET allocation.released_at = event_row.observed_at
WHERE allocation.mode = 'SHADOW'
  AND allocation.recipient_type = 'OWNER'
  AND allocation.recipient_profile_id IS NULL
  AND allocation.status = 'EXPIRED'
  AND event_row.event_type = 'EXPIRED'
  AND event_row.amount_kopecks = 0
  AND event_row.effective_at > event_row.observed_at;

UPDATE contractor_payment_allocation_events event_row
JOIN contractor_payment_allocations allocation
  ON allocation.id = event_row.allocation_id
SET event_row.effective_at = event_row.observed_at
WHERE allocation.mode = 'SHADOW'
  AND allocation.recipient_type = 'OWNER'
  AND allocation.recipient_profile_id IS NULL
  AND allocation.status = 'EXPIRED'
  AND event_row.event_type = 'EXPIRED'
  AND event_row.amount_kopecks = 0
  AND event_row.effective_at > event_row.observed_at;
