-- Incremented only by an explicitly approved restart, never by a timeout/retry.
-- Existing frozen operations and default generation zero retain their IDs.
ALTER TABLE scheduled_client_message_state
    ADD COLUMN dispatch_generation BIGINT NOT NULL DEFAULT 0;

CREATE TABLE client_reminder_restart_audit (
    request_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject_type VARCHAR(24) CHARACTER SET ascii NOT NULL,
    subject_id BIGINT NOT NULL,
    old_operation_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor VARCHAR(160) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    previous_snapshot MEDIUMTEXT NOT NULL,
    new_generation BIGINT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(request_key,subject_type,subject_id),
    UNIQUE KEY uk_reminder_restart_operation(old_operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
