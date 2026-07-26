ALTER TABLE manager_report_review_sessions
    ADD COLUMN issue_count INT NOT NULL DEFAULT 0 AFTER current_question_index,
    ADD COLUMN telegram_message_id INT NULL AFTER recipient_chat_id,
    ADD COLUMN question_message_id INT NULL AFTER telegram_message_id,
    ADD COLUMN reply_prompt_message_id INT NULL AFTER question_message_id,
    ADD COLUMN report_rich_snapshot LONGTEXT NULL AFTER report_snapshot,
    ADD COLUMN reading_confirmed_at DATETIME(6) NULL AFTER started_at,
    ADD COLUMN deadline_started_at DATETIME(6) NULL AFTER reading_confirmed_at,
    ADD COLUMN auto_completed TINYINT(1) NOT NULL DEFAULT 0 AFTER audit_required,
    ADD INDEX idx_manager_report_review_deadline (manager_user_id, deadline_started_at, completed_at);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('manager.report-review.max-question-count', '8', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
