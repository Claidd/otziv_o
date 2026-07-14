ALTER TABLE review_account_pool_alert_state
    ADD COLUMN last_required_count INT NOT NULL DEFAULT 0 AFTER cycle_number,
    ADD COLUMN last_notified_at DATETIME(6) NULL AFTER last_required_count;
