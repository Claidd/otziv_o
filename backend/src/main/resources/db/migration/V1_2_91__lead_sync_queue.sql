ALTER TABLE lead_sync_queue
    ADD COLUMN lead_id BIGINT NOT NULL AFTER id,
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_attempt_at DATETIME NULL,
    ADD COLUMN last_error VARCHAR(500) NULL;

