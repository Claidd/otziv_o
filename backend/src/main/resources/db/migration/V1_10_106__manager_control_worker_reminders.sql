ALTER TABLE manager_daily_control_concrete_items
    ADD COLUMN worker_reminder_sent_at DATETIME(6) NULL AFTER worker_explanation_by_user_id,
    ADD COLUMN worker_reminder_count INT NOT NULL DEFAULT 0 AFTER worker_reminder_sent_at,
    ADD INDEX idx_manager_control_worker_reminder (worker_explanation_at, worker_notification_sent_at, worker_reminder_sent_at);
