ALTER TABLE managers
    ADD COLUMN audit_telegram_group_url VARCHAR(500) NULL,
    ADD COLUMN audit_telegram_group_chat_id BIGINT NULL;

CREATE UNIQUE INDEX uk_managers_audit_telegram_group_chat_id
    ON managers (audit_telegram_group_chat_id);

INSERT INTO app_settings (setting_key, setting_value, updated_at)
VALUES ('manager.summary.manager-groups-enabled', 'true', CURRENT_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    updated_at = VALUES(updated_at);
