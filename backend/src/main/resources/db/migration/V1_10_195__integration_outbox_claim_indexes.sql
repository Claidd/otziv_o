-- Additive indexes matching the two independent outbox claim orderings.
-- V1_10_173 may already be applied, so its original composite index is retained.
CREATE INDEX idx_integration_outbox_pending_claim
    ON integration_outbox (status, available_at, integration_outbox_id);

CREATE INDEX idx_integration_outbox_stale_claim
    ON integration_outbox (status, processing_lease_until, integration_outbox_id);

-- Supports the correlated aggregate-head guard without scanning payload rows or
-- the full history of unrelated aggregates.
CREATE INDEX idx_integration_outbox_aggregate_head
    ON integration_outbox (
        aggregate_type,
        aggregate_id,
        status,
        integration_outbox_id
    );
