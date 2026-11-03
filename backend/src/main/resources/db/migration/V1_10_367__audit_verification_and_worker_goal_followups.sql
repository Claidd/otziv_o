ALTER TABLE manager_report_review_disputes
    ADD COLUMN verification_requested_by_system TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN verification_reason VARCHAR(2000) NULL;

ALTER TABLE manager_review_commitments
    MODIFY COLUMN source_task_id BIGINT NULL,
    MODIFY COLUMN due_at DATETIME(6) NULL,
    ADD COLUMN worker_id BIGINT NULL,
    ADD COLUMN worker_name VARCHAR(220) NULL,
    ADD COLUMN goal_date DATE NULL,
    ADD COLUMN goal_remaining BIGINT NULL,
    ADD COLUMN resolution_note VARCHAR(2000) NULL,
    ADD COLUMN resolved_at DATETIME(6) NULL,
    ADD INDEX idx_manager_commitment_worker_goal (manager_id, worker_id, goal_date, status);
