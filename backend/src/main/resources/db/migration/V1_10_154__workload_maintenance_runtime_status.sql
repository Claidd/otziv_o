-- Persistent heartbeat for workload self-healing and retention.
-- This migration only records maintenance state; it does not enable LIVE mode.

CREATE TABLE workload_maintenance_status (
    maintenance_task VARCHAR(24) NOT NULL,
    last_started_at DATETIME(6) NULL,
    last_succeeded_at DATETIME(6) NULL,
    last_failed_at DATETIME(6) NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(120) NULL,
    last_error_message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (maintenance_task)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO workload_maintenance_status (maintenance_task)
VALUES
    ('REPAIR'),
    ('RETENTION')
ON DUPLICATE KEY UPDATE maintenance_task = VALUES(maintenance_task);

-- The cross-manager emergency fallback is intentionally opt-in after CANARY
-- and end-to-end verification.
INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('workload.live.emergency-fallback-enabled', 'false', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    updated_at = VALUES(updated_at);

-- Manager-specific audit groups are no longer a delivery dependency: all
-- workload observations go to the shared admin/owner monitoring group.
UPDATE workload_shadow_events
SET active = FALSE,
    delivery_status = 'RESOLVED',
    next_attempt_at = NULL,
    processing_started_at = NULL,
    processing_lease_until = NULL,
    resolved_at = COALESCE(resolved_at, CURRENT_TIMESTAMP(6)),
    last_error_code = 'ROUTING_POLICY_CHANGED',
    last_error = 'Событие закрыто: используется общая группа администраторов и владельцев'
WHERE event_type = 'MISSING_MANAGER_GROUP'
  AND active = TRUE;
