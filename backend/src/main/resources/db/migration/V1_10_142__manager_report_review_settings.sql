ALTER TABLE managers
    ADD COLUMN report_review_enabled TINYINT(1) NOT NULL DEFAULT 1
        AFTER audit_telegram_group_chat_id;

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('manager.report-review.question-generation-max-tokens', '8000', CURRENT_TIMESTAMP(6)),
    ('manager.report-review.question-generation-retry-max-tokens', '12000', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
