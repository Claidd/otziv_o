-- Indexes for queries that ran as scans on every pass (2026-11-04 audit, PERF-25).
-- InnoDB builds secondary indexes online; these tables are append-heavy but moderate in size.

-- "Was this deferred card resolved later?" (manager review commitments, every minute):
-- filter on entity_key + status + resolved_at instead of scanning the manager's history.
ALTER TABLE manager_daily_control_concrete_items
    ADD INDEX idx_mdcci_entity_status_resolved (entity_key, item_status, resolved_at);

-- Reminder restart lookups by subject (common invoices, scheduled client messages).
ALTER TABLE client_reminder_restart_audit
    ADD INDEX idx_reminder_restart_subject (subject_type, subject_id, new_generation);
