ALTER TABLE workload_shadow_late_batches
    ADD COLUMN decision_code VARCHAR(16) NOT NULL DEFAULT 'LATE'
        AFTER section_code,
    ADD COLUMN decision_origin VARCHAR(32) NOT NULL DEFAULT 'LEGACY_LATE'
        AFTER decision_code,
    ADD COLUMN cohort_key VARCHAR(190) NULL
        AFTER decision_origin,
    ADD COLUMN source_available_at DATETIME(6) NULL
        AFTER remaining_estimated_minutes,
    ADD COLUMN available_minutes_at_decision BIGINT NULL
        AFTER source_available_at,
    ADD COLUMN cohort_estimated_minutes_at_decision BIGINT NULL
        AFTER available_minutes_at_decision,
    ADD INDEX idx_workload_shadow_decision_rollover (
        active,
        progress_date
    );

INSERT INTO app_settings (setting_key, setting_value, updated_at) VALUES
    ('workload.shadow.decision-retention-days', '60', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
