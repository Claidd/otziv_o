CREATE TABLE IF NOT EXISTS manager_report_review_sessions (
    review_id BIGINT NOT NULL AUTO_INCREMENT,
    summary_date DATE NOT NULL,
    manager_id BIGINT NOT NULL,
    manager_user_id BIGINT NOT NULL,
    manager_name VARCHAR(220) NOT NULL,
    recipient_chat_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DELIVERED',
    report_snapshot LONGTEXT NOT NULL,
    questions_json LONGTEXT NULL,
    answers_json LONGTEXT NULL,
    current_question_index INT NOT NULL DEFAULT 0,
    minimum_read_seconds INT NOT NULL DEFAULT 60,
    read_seconds BIGINT NOT NULL DEFAULT 0,
    answer_quality VARCHAR(32) NULL,
    answer_quality_reason VARCHAR(1000) NULL,
    action_plan VARCHAR(2000) NULL,
    dispute_text VARCHAR(2000) NULL,
    audit_required TINYINT(1) NOT NULL DEFAULT 0,
    delivered_at DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    disputed_at DATETIME(6) NULL,
    reminder_one_sent_at DATETIME(6) NULL,
    reminder_three_sent_at DATETIME(6) NULL,
    restricted_at DATETIME(6) NULL,
    restriction_released_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (review_id),
    UNIQUE KEY uk_manager_report_review_date_manager (summary_date, manager_id),
    INDEX idx_manager_report_review_pending (status, delivered_at),
    INDEX idx_manager_report_review_user (manager_user_id, created_at),
    INDEX idx_manager_report_review_audit (audit_required, disputed_at),
    CONSTRAINT fk_manager_report_review_manager
        FOREIGN KEY (manager_id) REFERENCES managers (manager_id)
);

CREATE TABLE IF NOT EXISTS manager_report_review_events (
    event_id BIGINT NOT NULL AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    actor_user_id BIGINT NULL,
    actor_role VARCHAR(24) NOT NULL,
    source VARCHAR(32) NOT NULL,
    payload_text LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id),
    INDEX idx_manager_report_review_event (review_id, created_at),
    INDEX idx_manager_report_review_event_actor (actor_user_id, created_at),
    CONSTRAINT fk_manager_report_review_event_review
        FOREIGN KEY (review_id) REFERENCES manager_report_review_sessions (review_id) ON DELETE CASCADE
);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('manager.report-review.enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('manager.report-review.question-count', '2', CURRENT_TIMESTAMP(6)),
    ('manager.report-review.minimum-read-seconds', '60', CURRENT_TIMESTAMP(6)),
    ('manager.report-review.reminder-one-minutes', '60', CURRENT_TIMESTAMP(6)),
    ('manager.report-review.reminder-three-minutes', '180', CURRENT_TIMESTAMP(6)),
    ('manager.report-review.restriction-enabled', 'true', CURRENT_TIMESTAMP(6)),
    ('manager.report-review.ai-timeout-seconds', '25', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
