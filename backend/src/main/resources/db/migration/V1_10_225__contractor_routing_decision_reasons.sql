ALTER TABLE contractor_payment_allocations
    ADD COLUMN routing_decision_reason VARCHAR(64) NULL AFTER status,
    ADD COLUMN specialist_rejection_reason VARCHAR(64) NULL AFTER routing_decision_reason,
    ADD COLUMN manager_rejection_reason VARCHAR(64) NULL AFTER specialist_rejection_reason;

ALTER TABLE contractor_payment_allocation_events
    ADD COLUMN routing_decision_reason VARCHAR(64) NULL AFTER status_after,
    ADD COLUMN specialist_rejection_reason VARCHAR(64) NULL AFTER routing_decision_reason,
    ADD COLUMN manager_rejection_reason VARCHAR(64) NULL AFTER specialist_rejection_reason;

UPDATE contractor_payment_allocations
SET routing_decision_reason = 'LEGACY_UNCLASSIFIED'
WHERE status = 'OWNER_FALLBACK'
  AND routing_decision_reason IS NULL;

UPDATE contractor_payment_allocation_events event_row
JOIN contractor_payment_allocations allocation
  ON allocation.id = event_row.allocation_id
SET event_row.routing_decision_reason = allocation.routing_decision_reason
WHERE event_row.event_type = 'OWNER_FALLBACK'
  AND event_row.routing_decision_reason IS NULL;

CREATE INDEX idx_contractor_allocations_routing_reason
    ON contractor_payment_allocations (
        mode,
        status,
        routing_decision_reason,
        created_at
    );
