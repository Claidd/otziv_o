ALTER TABLE manager_daily_control_concrete_items
    ADD COLUMN worker_notification_user_id BIGINT NULL AFTER worker_notification_attempted_at,
    ADD COLUMN worker_explanation_requested_at DATETIME(6) NULL AFTER worker_notification_failure_reason,
    ADD COLUMN worker_explanation_prompted_at DATETIME(6) NULL AFTER worker_explanation_requested_at,
    ADD COLUMN worker_explanation VARCHAR(1000) NULL AFTER worker_explanation_prompted_at,
    ADD COLUMN worker_explanation_at DATETIME(6) NULL AFTER worker_explanation,
    ADD COLUMN worker_explanation_by_user_id BIGINT NULL AFTER worker_explanation_at,
    ADD INDEX idx_manager_control_worker_explanation (worker_notification_user_id, worker_notification_sent_at, worker_explanation_at);
