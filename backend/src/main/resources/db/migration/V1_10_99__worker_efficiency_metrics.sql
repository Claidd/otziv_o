ALTER TABLE worker_work_item_lifecycle
    ADD COLUMN available_at DATETIME(6) NULL AFTER opened_at,
    ADD COLUMN due_at DATETIME(6) NULL AFTER available_at,
    ADD COLUMN effective_close_seconds BIGINT NOT NULL DEFAULT 0 AFTER closed_at,
    ADD COLUMN overdue BIT NOT NULL DEFAULT 0 AFTER active;

ALTER TABLE worker_daily_performance
    ADD COLUMN opened_count BIGINT NOT NULL DEFAULT 0 AFTER total_count,
    ADD COLUMN order_completed_count BIGINT NOT NULL DEFAULT 0 AFTER checked,
    ADD COLUMN nagul_completed_count BIGINT NOT NULL DEFAULT 0 AFTER order_completed_count,
    ADD COLUMN publish_completed_count BIGINT NOT NULL DEFAULT 0 AFTER nagul_completed_count,
    ADD COLUMN bad_completed_count BIGINT NOT NULL DEFAULT 0 AFTER publish_completed_count,
    ADD COLUMN recovery_completed_count BIGINT NOT NULL DEFAULT 0 AFTER bad_completed_count,
    ADD COLUMN recovery_created_count BIGINT NOT NULL DEFAULT 0 AFTER recovery_completed_count,
    ADD COLUMN order_overdue_count BIGINT NOT NULL DEFAULT 0 AFTER recovery_created_count,
    ADD COLUMN total_overdue_count BIGINT NOT NULL DEFAULT 0 AFTER order_overdue_count,
    ADD COLUMN speed_score INT NOT NULL DEFAULT 0 AFTER p90_close_seconds,
    ADD COLUMN discipline_score INT NOT NULL DEFAULT 0 AFTER speed_score,
    ADD COLUMN workload_score INT NOT NULL DEFAULT 0 AFTER discipline_score,
    ADD COLUMN bot_change_count BIGINT NOT NULL DEFAULT 0 AFTER activity_events,
    ADD COLUMN bot_block_count BIGINT NOT NULL DEFAULT 0 AFTER bot_change_count;

ALTER TABLE worker_performance_monthly
    ADD COLUMN opened_count BIGINT NOT NULL DEFAULT 0 AFTER total_count,
    ADD COLUMN order_completed_count BIGINT NOT NULL DEFAULT 0 AFTER checked_days,
    ADD COLUMN nagul_completed_count BIGINT NOT NULL DEFAULT 0 AFTER order_completed_count,
    ADD COLUMN publish_completed_count BIGINT NOT NULL DEFAULT 0 AFTER nagul_completed_count,
    ADD COLUMN bad_completed_count BIGINT NOT NULL DEFAULT 0 AFTER publish_completed_count,
    ADD COLUMN recovery_completed_count BIGINT NOT NULL DEFAULT 0 AFTER bad_completed_count,
    ADD COLUMN recovery_created_count BIGINT NOT NULL DEFAULT 0 AFTER recovery_completed_count,
    ADD COLUMN order_overdue_count BIGINT NOT NULL DEFAULT 0 AFTER recovery_created_count,
    ADD COLUMN total_overdue_count BIGINT NOT NULL DEFAULT 0 AFTER order_overdue_count,
    ADD COLUMN average_speed_score DECIMAL(5,2) NOT NULL DEFAULT 0 AFTER p90_close_seconds,
    ADD COLUMN average_discipline_score DECIMAL(5,2) NOT NULL DEFAULT 0 AFTER average_speed_score,
    ADD COLUMN average_workload_score DECIMAL(5,2) NOT NULL DEFAULT 0 AFTER average_discipline_score,
    ADD COLUMN bot_change_count BIGINT NOT NULL DEFAULT 0 AFTER activity_events,
    ADD COLUMN bot_block_count BIGINT NOT NULL DEFAULT 0 AFTER bot_change_count;

CREATE INDEX idx_worker_lifecycle_due_active
    ON worker_work_item_lifecycle (worker_id, active, due_at);

CREATE INDEX idx_worker_lifecycle_type_closed
    ON worker_work_item_lifecycle (item_type, closed_at);

CREATE INDEX idx_worker_activity_worker_action_created
    ON worker_activity_events (worker_user_id, action, created_at);

CREATE INDEX idx_recovery_tasks_worker_created
    ON review_recovery_tasks (review_recovery_task_worker, review_recovery_task_created_at);

INSERT INTO app_settings (setting_key, setting_value, updated_at) VALUES
    ('worker.progress.night-window-start-hour', '0', CURRENT_TIMESTAMP(6)),
    ('worker.progress.night-window-end-hour', '10', CURRENT_TIMESTAMP(6)),
    ('worker.progress.late-task-hour', '22', CURRENT_TIMESTAMP(6)),
    ('worker.progress.late-task-deadline-hour', '12', CURRENT_TIMESTAMP(6)),
    ('worker.progress.speed-target-minutes', '240', CURRENT_TIMESTAMP(6)),
    ('worker.progress.expected-daily-load', '15', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
