ALTER TABLE manager_report_review_sessions
    ADD COLUMN questions_source VARCHAR(24) NULL AFTER questions_json,
    ADD COLUMN question_sent_at DATETIME(6) NULL AFTER reply_prompt_message_id,
    ADD COLUMN ai_unavailable_started_at DATETIME(6) NULL AFTER deadline_started_at,
    ADD COLUMN ai_unavailable_seconds BIGINT NOT NULL DEFAULT 0 AFTER ai_unavailable_started_at,
    ADD COLUMN suspicious_answer_count INT NOT NULL DEFAULT 0 AFTER answer_quality_reason;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('manager.report-review.minimum-answer-score', '75', CURRENT_TIMESTAMP(6)),
    ('manager.report-review.fast-paste-seconds', '12', CURRENT_TIMESTAMP(6)),
    ('manager.report-review.fast-paste-min-characters', '140', CURRENT_TIMESTAMP(6)),
    ('manager.report-review.max-answer-characters', '420', CURRENT_TIMESTAMP(6)),
    ('manager.report-review.max-plan-characters', '600', CURRENT_TIMESTAMP(6)),
    ('manager.report-review.copy-gram-size', '4', CURRENT_TIMESTAMP(6)),
    ('manager.report-review.copy-similarity-percent', '65', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
