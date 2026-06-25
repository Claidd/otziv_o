ALTER TABLE worker_risk_incidents
    ADD COLUMN rollback_status VARCHAR(30) NULL,
    ADD COLUMN rolled_back_at DATETIME NULL,
    ADD COLUMN rolled_back_by_user_id BIGINT NULL,
    ADD COLUMN rolled_back_by_username VARCHAR(150) NULL,
    ADD COLUMN rollback_message TEXT NULL;
