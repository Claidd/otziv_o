ALTER TABLE manager_daily_control_concrete_items
    ADD COLUMN worker_notification_attempted_at DATETIME(6) NULL AFTER last_manual_touch_at,
    ADD COLUMN worker_notification_sent_at DATETIME(6) NULL AFTER worker_notification_attempted_at,
    ADD COLUMN worker_notification_accepted_at DATETIME(6) NULL AFTER worker_notification_sent_at,
    ADD COLUMN worker_notification_accepted_by_user_id BIGINT NULL AFTER worker_notification_accepted_at,
    ADD COLUMN worker_notification_failure_reason VARCHAR(500) NULL AFTER worker_notification_accepted_by_user_id,
    ADD INDEX idx_manager_control_worker_notification (worker_notification_sent_at, worker_notification_accepted_at);
