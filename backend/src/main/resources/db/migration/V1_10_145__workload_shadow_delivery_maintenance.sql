ALTER TABLE workload_shadow_events
    ADD COLUMN processing_started_at DATETIME(6) NULL AFTER next_attempt_at,
    ADD COLUMN processing_lease_until DATETIME(6) NULL AFTER processing_started_at,
    ADD COLUMN last_error_code VARCHAR(80) NULL AFTER delivered_at,
    ADD INDEX idx_workload_shadow_event_processing (delivery_status, processing_lease_until);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('workload.shadow.notification-batch-size', '10', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.notification-max-attempts', '8', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.notification-lease-minutes', '5', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.notification-retry-base-minutes', '1', CURRENT_TIMESTAMP(6)),
    ('workload.shadow.maintenance-batch-size', '1000', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
