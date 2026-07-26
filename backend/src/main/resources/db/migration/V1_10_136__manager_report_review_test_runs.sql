ALTER TABLE manager_report_review_sessions
    DROP INDEX uk_manager_report_review_date_manager,
    ADD COLUMN test_mode TINYINT(1) NOT NULL DEFAULT 0 AFTER manager_name,
    ADD COLUMN test_owner_user_id BIGINT NULL AFTER test_mode,
    ADD COLUMN test_run_id BIGINT NOT NULL DEFAULT 0 AFTER test_owner_user_id,
    ADD UNIQUE KEY uk_manager_report_review_run (summary_date, manager_id, test_run_id),
    ADD INDEX idx_manager_report_review_test_owner (test_mode, test_owner_user_id, created_at);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('manager.report-review.test-minimum-read-seconds', '10', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
