ALTER TABLE manager_report_review_sessions
    ADD COLUMN recovery_context_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN recovery_context_checked_at DATETIME(6) NULL;
