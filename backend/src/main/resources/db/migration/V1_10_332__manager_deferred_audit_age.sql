ALTER TABLE manager_daily_control_concrete_items
    ADD COLUMN deferred_at DATETIME NULL;

UPDATE manager_daily_control_concrete_items
SET deferred_at = COALESCE(updated_at, created_at)
WHERE item_status <> 'RESOLVED'
  AND action_type = 'DEFERRED';

CREATE INDEX idx_manager_concrete_deferred_at
    ON manager_daily_control_concrete_items (deferred_at, item_status, entity_key);
