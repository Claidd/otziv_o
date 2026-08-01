-- V191 has already shipped and must remain immutable. Keep the later SLA
-- delivery-claim additions in their own forward-only migration so existing
-- installations and clean databases converge on the same schema.
ALTER TABLE worker_risk_incidents
    ADD COLUMN sla_delivery_claim_token VARCHAR(36) NULL,
    ADD COLUMN sla_delivery_claimed_at DATETIME(6) NULL,
    ADD COLUMN sla_delivery_claim_kind VARCHAR(16) NULL,
    ADD INDEX idx_worker_risk_sla_cursor (status, response_due_at, incident_id),
    ALGORITHM=INPLACE,
    LOCK=NONE;
