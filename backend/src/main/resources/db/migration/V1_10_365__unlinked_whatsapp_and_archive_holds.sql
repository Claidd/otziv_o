CREATE TABLE whatsapp_unlinked_events (
    receipt_key CHAR(64) NOT NULL PRIMARY KEY,
    client_id VARCHAR(512) NOT NULL,
    group_id VARCHAR(512) NOT NULL,
    payload_json JSON NOT NULL,
    reason VARCHAR(64) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    KEY idx_unlinked_due (completed_at,next_attempt_at)
);

ALTER TABLE scheduled_client_message_state
    ADD COLUMN archive_next_check_at DATETIME(6) NULL,
    ADD COLUMN archive_block_reason VARCHAR(500) NULL;
