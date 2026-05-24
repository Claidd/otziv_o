ALTER TABLE companies
    ADD COLUMN company_publication_progress_reports_enabled TINYINT(1) NOT NULL DEFAULT 1;

INSERT IGNORE INTO app_settings (setting_key, setting_value, updated_at)
VALUES
    ('client.publication-progress-reports.enabled', 'true', CURRENT_TIMESTAMP(6));
