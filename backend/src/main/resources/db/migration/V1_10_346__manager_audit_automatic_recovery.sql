-- Existing paused audits are eligible automatically; no answer or deadline is rewritten.
ALTER TABLE manager_report_review_sessions
    ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN recovery_next_attempt_at DATETIME(6) NULL,
    ADD COLUMN recovery_awaiting_context BOOLEAN NOT NULL DEFAULT FALSE,
    ADD INDEX idx_manager_review_recovery (completed_at, ai_unavailable_started_at, recovery_next_attempt_at);
