ALTER TABLE manager_daily_controls
    ADD COLUMN morning_started_at DATETIME NULL,
    ADD COLUMN morning_completed_at DATETIME NULL,
    ADD COLUMN day_checked_at DATETIME NULL,
    ADD COLUMN final_checked_at DATETIME NULL,
    ADD COLUMN closed_by_user_id BIGINT NULL,
    ADD COLUMN quality_score INT NOT NULL DEFAULT 0,
    ADD COLUMN quality_grade VARCHAR(20) NULL,
    ADD COLUMN risk_score INT NOT NULL DEFAULT 0,
    ADD COLUMN fast_click_risk BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN morning_notification_sent_at DATETIME NULL,
    ADD COLUMN day_notification_sent_at DATETIME NULL,
    ADD COLUMN evening_notification_sent_at DATETIME NULL,
    ADD COLUMN owner_notification_sent_at DATETIME NULL;
